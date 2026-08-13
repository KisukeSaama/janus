package io.janus.providers;

import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import io.janus.gateway.GatewayHttpClientConfig;

/**
 * Knocks on a registered destination and reports whether anybody answered.
 *
 * <p>The probe travels the gateway's own client, so it obeys exactly the same address rules as a
 * proxied call: a diagnostic must not become the one way to make Janus connect to a private address.
 * It is a HEAD of the destination's base URL and nothing more — no credential, no path, no body read
 * — because the only question is whether the address is still answering. Its deadline is its own and
 * much shorter than a proxied call's: somebody is waiting in front of a console for this one.
 */
@Component
public class UpstreamPing {
    private final WebClient web;
    private final WebClient privateWeb;
    private final DestinationValidator destinations;
    private final Duration timeout;

    public UpstreamPing(
            WebClient gatewayWebClient,
            WebClient gatewayPrivateWebClient,
            DestinationValidator destinations,
            @Value("${janus.gateway.ping-timeout-seconds:5}") long timeoutSeconds) {
        this.web = gatewayWebClient;
        this.privateWeb = gatewayPrivateWebClient;
        this.destinations = destinations;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    public ProviderPing reach(String baseUrl) {
        return reach(baseUrl, false);
    }

    /**
     * @param privateDestination whether this destination is registered as being on a local network,
     *     which the probe must obey too — otherwise the console would report a LAN service as blocked
     *     while the gateway reaches it perfectly well
     */
    public ProviderPing reach(String baseUrl, boolean privateDestination) {
        long started = System.nanoTime();
        try {
            // The shape only, as the gateway does on every request: where the name currently points
            // is checked at connection time, which is the check a probe cannot get out of date.
            var target = destinations.validateShape(baseUrl, privateDestination);
            var status = (privateDestination ? privateWeb : web)
                    .head()
                    .uri(target)
                    // Released rather than read: an answer's body is somebody's home page, and this
                    // asks a yes-or-no question.
                    .exchangeToMono(response -> response.releaseBody().thenReturn(response.statusCode()))
                    .timeout(timeout)
                    .block();
            return status == null
                    ? ProviderPing.failed(ProviderPing.Reason.UNREACHABLE, elapsed(started))
                    : ProviderPing.answered(status.value(), elapsed(started));
        } catch (Exception failure) {
            return ProviderPing.failed(reasonFor(failure), elapsed(started));
        }
    }

    /**
     * The cause, named. Reactor wraps a transport failure in one or two layers of its own, so the
     * chain is walked rather than the outermost exception matched — and only the category travels
     * back, never the message, which for a TLS or connection failure quotes internal detail.
     */
    private static ProviderPing.Reason reasonFor(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof GatewayHttpClientConfig.BlockedDestinationException)
                return ProviderPing.Reason.BLOCKED;
            if (cause instanceof TimeoutException) return ProviderPing.Reason.TIMED_OUT;
            if (cause instanceof UnknownHostException) return ProviderPing.Reason.UNRESOLVED;
            if (cause instanceof SSLException) return ProviderPing.Reason.TLS_FAILED;
            if (cause.getCause() == cause) break;
        }
        return ProviderPing.Reason.UNREACHABLE;
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
