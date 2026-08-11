package io.janus.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class HeaderPolicyTest {

    @Test
    void janusAuthenticationHeadersNeverReachTheUpstream() {
        assertThat(HeaderPolicy.isRequestHeaderForwarded("X-Janus-Api-Key")).isFalse();
        assertThat(HeaderPolicy.isRequestHeaderForwarded("x-janus-application-id"))
                .isFalse();
    }

    @Test
    void callerSuppliedAuthorizationAndCookiesAreNotForwarded() {
        assertThat(HeaderPolicy.isRequestHeaderForwarded("Authorization")).isFalse();
        assertThat(HeaderPolicy.isRequestHeaderForwarded("Cookie")).isFalse();
        assertThat(HeaderPolicy.isRequestHeaderForwarded("Proxy-Authorization")).isFalse();
    }

    @Test
    void hopByHopAndFramingHeadersAreNotForwarded() {
        for (String header : new String[] {
            "Connection",
            "Keep-Alive",
            "Proxy-Connection",
            "TE",
            "Trailer",
            "Transfer-Encoding",
            "Upgrade",
            "Expect",
            "Content-Length",
            "Host",
            "Accept-Encoding"
        })
            assertThat(HeaderPolicy.isRequestHeaderForwarded(header))
                    .withFailMessage("expected %s to be dropped", header)
                    .isFalse();
    }

    @Test
    void theCallerCannotShapeWhatTheUpstreamSeesAboutTheInboundHop() {
        for (String header : new String[] {
            "Via",
            "Forwarded",
            "X-Forwarded-For",
            "X-Forwarded-Host",
            "X-Forwarded-Proto",
            "X-Forwarded-Port",
            "X-Real-IP",
            "X-Correlation-Id"
        })
            assertThat(HeaderPolicy.isRequestHeaderForwarded(header))
                    .withFailMessage("expected %s to be dropped", header)
                    .isFalse();
    }

    @Test
    void ordinaryRequestHeadersAreForwarded() {
        assertThat(HeaderPolicy.isRequestHeaderForwarded("Accept")).isTrue();
        assertThat(HeaderPolicy.isRequestHeaderForwarded("Content-Type")).isTrue();
        assertThat(HeaderPolicy.isRequestHeaderForwarded("If-None-Match")).isTrue();
    }

    @Test
    void upstreamAuthenticationAndSessionHeadersAreNotReturned() {
        var upstream = new HttpHeaders();
        upstream.add("Set-Cookie", "session=abc");
        upstream.add("WWW-Authenticate", "Basic realm=\"upstream\"");
        upstream.add("Authorization", "Bearer leaked");
        upstream.add("Proxy-Authenticate", "Basic");
        upstream.add("Content-Type", "application/json");
        upstream.add("ETag", "\"v1\"");

        var returned = HeaderPolicy.filterResponseHeaders(upstream);
        assertThat(returned.headerNames()).containsExactlyInAnyOrder("Content-Type", "ETag");
    }

    /**
     * The X-Janus- namespace is Janus's own voice. Some of it is written only conditionally — the
     * rate-limit trio when a quota exists, the attempt count after a retry — so an upstream left
     * free to supply the rest could tell a caller it had allowance it does not have, or that an
     * answer came from the store when it came from the upstream.
     */
    @Test
    void anUpstreamCannotForgeWhatJanusSaysAboutACall() {
        var upstream = new HttpHeaders();
        upstream.add("X-Janus-RateLimit-Remaining", "999999");
        upstream.add("X-Janus-Upstream-Attempts", "1");
        upstream.add("x-janus-cache", "HIT");
        upstream.add("X-Janus-Correlation-Id", "somebody-elses");
        upstream.add("Content-Type", "application/json");

        assertThat(HeaderPolicy.filterResponseHeaders(upstream).headerNames()).containsExactly("Content-Type");
    }

    /** The same rule outbound: a caller must not make an upstream believe the gateway said something. */
    @Test
    void aCallerCannotSpeakInJanusNameToTheUpstream() {
        assertThat(HeaderPolicy.isRequestHeaderForwarded("X-Janus-Correlation-Id"))
                .isFalse();
        assertThat(HeaderPolicy.isRequestHeaderForwarded("x-janus-anything")).isFalse();
    }

    @Test
    void contentLengthIsDroppedBecauseTheBodyMayBeRewritten() {
        var upstream = new HttpHeaders();
        upstream.add("Content-Length", "512");
        assertThat(HeaderPolicy.filterResponseHeaders(upstream).headerNames()).isEmpty();
    }
}
