package io.janus.providers;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.UnknownHostException;

import javax.net.ssl.SSLHandshakeException;

import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;

import io.janus.gateway.GatewayHttpClientConfig.BlockedDestinationException;

/**
 * The probe behind "is this API still there".
 *
 * <p>What matters here is the reading it gives, not the request it makes: an operator acts on the
 * answer, so a refusal has to read as reached and each transport failure has to name the layer that
 * gave way. Reactor wraps those failures in a layer or two of its own, which is the whole reason the
 * mapping is worth a test.
 */
class UpstreamPingTest {
    private static final String DESTINATION = "https://api.spotify.com";

    private UpstreamPing answering(HttpStatus status) {
        return pingWith(Mono.just(ClientResponse.create(status).build()), 5);
    }

    private UpstreamPing failing(Throwable cause) {
        var wrapped = new WebClientRequestException(cause, HttpMethod.HEAD, URI.create(DESTINATION), new HttpHeaders());
        return pingWith(Mono.error(wrapped), 5);
    }

    private UpstreamPing pingWith(Mono<ClientResponse> answer, long timeoutSeconds) {
        var web = WebClient.builder().exchangeFunction(request -> answer).build();
        return new UpstreamPing(web, web, new DestinationValidator(false, false), timeoutSeconds);
    }

    /** The question is whether anybody is listening, and a 401 is somebody listening. */
    @Test
    void aRefusalStillCountsAsReached() {
        var result = answering(HttpStatus.UNAUTHORIZED).reach(DESTINATION);

        assertThat(result.reachable()).isTrue();
        assertThat(result.status()).isEqualTo(401);
        assertThat(result.reason()).isEqualTo(ProviderPing.Reason.ANSWERED);
    }

    @Test
    void namesAHostThatDoesNotResolve() {
        var result = failing(new UnknownHostException("api.spotify.com")).reach(DESTINATION);

        assertThat(result.reachable()).isFalse();
        assertThat(result.status()).isZero();
        assertThat(result.reason()).isEqualTo(ProviderPing.Reason.UNRESOLVED);
    }

    @Test
    void namesACertificateThatCouldNotBeAccepted() {
        var result = failing(new SSLHandshakeException("certificate expired")).reach(DESTINATION);

        assertThat(result.reason()).isEqualTo(ProviderPing.Reason.TLS_FAILED);
    }

    /**
     * A destination probed from the console is subject to the same address rules as a proxied call,
     * so a name that resolves inside the deployment is refused here too — and says so, rather than
     * reading as an API that happens to be down.
     */
    @Test
    void namesADestinationTheGatewayIsNotAllowedToReach() {
        var result = failing(new BlockedDestinationException("private address")).reach(DESTINATION);

        assertThat(result.reason()).isEqualTo(ProviderPing.Reason.BLOCKED);
    }

    /** Silence is an answer, once the probe's own deadline has passed. */
    @Test
    void givesUpOnItsOwnDeadlineRatherThanTheGatewaysOne() {
        var result = pingWith(Mono.never(), 0).reach(DESTINATION);

        assertThat(result.reachable()).isFalse();
        assertThat(result.reason()).isEqualTo(ProviderPing.Reason.TIMED_OUT);
    }

    /** Anything else is reported as what it is rather than guessed at. */
    @Test
    void fallsBackToUnreachableForAnythingElse() {
        var result =
                failing(new java.net.ConnectException("connection refused")).reach(DESTINATION);

        assertThat(result.reason()).isEqualTo(ProviderPing.Reason.UNREACHABLE);
    }
}
