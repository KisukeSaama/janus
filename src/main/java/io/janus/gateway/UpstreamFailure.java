package io.janus.gateway;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLException;

import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import io.janus.shared.ErrorCode;

/**
 * What actually went wrong when a proxied call never produced a response, stated in the terms a
 * caller can act on.
 *
 * <p>All of these used to be answered the same way: 502, "Upstream request failed". That answer is
 * wrong twice over. It tells a client the provider is broken when the provider may simply be slow —
 * a distinction that decides whether retrying is sensible — and it says the same thing when the
 * fault is Janus's own, sending someone to read an upstream's status page over a defect here.
 *
 * <p>So a timeout is a 504, an unreachable host is a 502, and anything this class cannot recognise
 * is a 500: not the upstream's fault until something says it is.
 *
 * @param status what the caller is answered with
 * @param code   the stable name of the failure
 * @param detail prose, and never the exception's own message — that can quote internal detail
 */
record UpstreamFailure(HttpStatus status, ErrorCode code, String detail) {

    /** Bounded, because a cause chain can be circular. */
    private static final int MAX_DEPTH = 12;

    static UpstreamFailure of(Throwable failure) {
        // The whole chain is walked before the generic answer is used. What the client library
        // throws is a wrapper — the timeout or the unresolved name is always underneath it — so
        // matching the outermost transport exception first would describe every one of these as
        // "could not be reached" and lose the only part worth reading.
        boolean transport = false;
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < MAX_DEPTH; cause = cause.getCause(), depth++) {
            if (cause instanceof ReadTimeoutException
                    || cause instanceof ConnectTimeoutException
                    || cause instanceof TimeoutException)
                return new UpstreamFailure(
                        HttpStatus.GATEWAY_TIMEOUT,
                        ErrorCode.UPSTREAM_TIMEOUT,
                        "The provider did not answer within the configured timeout");
            if (cause instanceof UnknownHostException)
                return unreachable("The provider's hostname could not be resolved");
            if (cause instanceof ConnectException) return unreachable("The provider refused the connection");
            if (cause instanceof SSLException) return unreachable("The TLS handshake with the provider failed");
            transport |= cause instanceof WebClientRequestException || cause instanceof IOException;
            if (cause.getCause() == cause) break;
        }
        if (transport) return unreachable("The provider could not be reached");
        return new UpstreamFailure(
                HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "The request could not be completed");
    }

    private static UpstreamFailure unreachable(String detail) {
        return new UpstreamFailure(HttpStatus.BAD_GATEWAY, ErrorCode.UPSTREAM_UNREACHABLE, detail);
    }
}
