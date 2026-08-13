package io.janus.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;

import javax.net.ssl.SSLHandshakeException;

import io.netty.handler.timeout.ReadTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import io.janus.shared.ErrorCode;

/**
 * Which of the ways a proxied call can fail without producing a response is which. All of them used
 * to be one answer — 502, "Upstream request failed" — which said the provider was broken when it may
 * only have been slow, and said the same thing when the fault was Janus's own.
 */
class UpstreamFailureTest {

    private static WebClientRequestException wrapped(Throwable cause) {
        return new WebClientRequestException(
                cause, HttpMethod.GET, URI.create("https://api.example.com/v1"), HttpHeaders.EMPTY);
    }

    /** The client library's exception is always a wrapper; the part worth reading is underneath it. */
    @Test
    void looksThroughTheClientLibrarysWrapper() {
        assertThat(UpstreamFailure.of(wrapped(ReadTimeoutException.INSTANCE)))
                .returns(HttpStatus.GATEWAY_TIMEOUT, UpstreamFailure::status)
                .returns(ErrorCode.UPSTREAM_TIMEOUT, UpstreamFailure::code);

        assertThat(UpstreamFailure.of(wrapped(new UnknownHostException("nowhere.invalid"))))
                .returns(HttpStatus.BAD_GATEWAY, UpstreamFailure::status)
                .returns(ErrorCode.UPSTREAM_UNREACHABLE, UpstreamFailure::code)
                .returns("The provider's hostname could not be resolved", UpstreamFailure::detail);
    }

    @Test
    void namesEachWayAConnectionCanFail() {
        assertThat(UpstreamFailure.of(wrapped(new ConnectException())).detail()).contains("refused the connection");
        assertThat(UpstreamFailure.of(wrapped(new SSLHandshakeException("bad certificate")))
                        .detail())
                .contains("TLS handshake");
        assertThat(UpstreamFailure.of(wrapped(new IOException("broken pipe"))).detail())
                .contains("could not be reached");
    }

    /** A defect here is a 500. Answering 502 sends somebody to read an upstream's status page. */
    @Test
    void aFailureWithNoTransportCauseIsJanusSOwn() {
        assertThat(UpstreamFailure.of(new IllegalStateException("null somewhere in Janus")))
                .returns(HttpStatus.INTERNAL_SERVER_ERROR, UpstreamFailure::status)
                .returns(ErrorCode.INTERNAL_ERROR, UpstreamFailure::code);
    }

    /** Nothing in the exception's own message travels back, whichever branch answers. */
    @Test
    void neverQuotesTheExceptionItSaw() {
        var secret = new IllegalStateException("jdbc://user:hunter2@db/janus");

        assertThat(UpstreamFailure.of(secret).detail()).doesNotContain("hunter2");
        assertThat(UpstreamFailure.of(wrapped(secret)).detail()).doesNotContain("hunter2");
    }

    /** A cause chain can be circular, and walking one must not become the outage. */
    @Test
    void survivesACircularCauseChain() {
        var first = new IllegalStateException("first");
        var second = new IllegalStateException("second");
        first.initCause(second);
        second.initCause(first);

        assertThat(UpstreamFailure.of(first).code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
    }
}
