package io.janus.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.*;

/**
 * The body limit. Spring materialises a proxied body as a byte array before the controller runs, so
 * this filter is the only thing standing between one caller and an arbitrary amount of heap.
 */
class GatewayRequestSizeFilterTest {
    private static final int LIMIT = 100;

    private final GatewayRequestSizeFilter filter = new GatewayRequestSizeFilter(LIMIT);

    private MockHttpServletResponse refuseOrPass(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    /**
     * A chunked body announces no length, which is the case the filter cannot decide up front. The
     * mock derives one from the content it is given, so an undeclared body has to be stated here.
     */
    private static MockHttpServletRequest gatewayRequest(byte[] body, boolean declareLength) {
        var request = declareLength
                ? new MockHttpServletRequest("POST", "/gateway/spotify/v1/tracks")
                : new MockHttpServletRequest("POST", "/gateway/spotify/v1/tracks") {
                    @Override
                    public long getContentLengthLong() {
                        return -1;
                    }

                    @Override
                    public int getContentLength() {
                        return -1;
                    }
                };
        request.setContent(body);
        return request;
    }

    @Test
    void letsAnOrdinaryBodyThrough() throws Exception {
        var response = refuseOrPass(gatewayRequest(new byte[LIMIT], true));

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    /** A declared length is refused before a single byte has been read. */
    @Test
    void refusesADeclaredLengthOverTheLimitWithoutReadingTheBody() throws Exception {
        var response = refuseOrPass(gatewayRequest(new byte[LIMIT + 1], true));

        assertThat(response.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
        assertThat(response.getContentAsString()).contains("Payload Too Large");
        assertThat(response.getContentType()).contains("application/problem+json");
    }

    /** Without a declared length the body has to be cut off while it is being read. */
    @Test
    void cutsOffAnUndeclaredBodyThatGrowsPastTheLimit() throws Exception {
        var request = gatewayRequest(new byte[LIMIT + 1], false);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> req.getInputStream().readAllBytes());

        assertThat(response.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
    }

    @Test
    void readsAnUndeclaredBodyThatStaysWithinTheLimit() throws Exception {
        var request = gatewayRequest("small".getBytes(StandardCharsets.UTF_8), false);
        var response = new MockHttpServletResponse();
        var read = new byte[1][];

        filter.doFilter(
                request, response, (req, res) -> read[0] = req.getInputStream().readAllBytes());

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(read[0]).asString().isEqualTo("small");
    }

    /** The administration API has its own body limits; this one is about what gets proxied. */
    @Test
    void doesNotApplyToRequestsThatAreNotProxied() {
        var request = new MockHttpServletRequest("POST", "/api/admin/providers");

        assertThat(filter.shouldNotFilter(request)).isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/gateway/spotify/v1")))
                .isFalse();
    }
}
