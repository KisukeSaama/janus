package io.janus.gateway;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class GatewayPathTest {

    private static GatewayPath parse(String uri) {
        return GatewayPath.parse(uri, "example", null);
    }

    /**
     * A servlet filter reads the URI with the container's prefix on it; everything else in the chain
     * sees it removed. Asking the container how long its own prefix is is what keeps the two
     * agreeing, and is what a pattern matching an optional leading segment never did.
     */
    @Test
    void theApplicationPathIsWhatIsLeftAfterTheContextPath() {
        var deployed = new org.springframework.mock.web.MockHttpServletRequest("GET", "/janus/gateway/example/v1");
        deployed.setContextPath("/janus");
        assertThat(GatewayPath.applicationPath(deployed)).isEqualTo("/gateway/example/v1");

        var atTheRoot = new org.springframework.mock.web.MockHttpServletRequest("GET", "/gateway/example/v1");
        assertThat(GatewayPath.applicationPath(atTheRoot)).isEqualTo("/gateway/example/v1");
    }

    /** A path that is not under the prefix the container reports is left exactly as it arrived. */
    @Test
    void aPathOutsideTheContextPathIsNotTrimmed() {
        var odd = new org.springframework.mock.web.MockHttpServletRequest("GET", "/elsewhere/gateway/example");
        odd.setContextPath("/janus");
        assertThat(GatewayPath.applicationPath(odd)).isEqualTo("/elsewhere/gateway/example");
    }

    @Test
    void extractsThePathThatFollowsTheProviderSlug() {
        var path = parse("/gateway/example/v1/customers/42");
        assertThat(path.rawPath()).isEqualTo("/v1/customers/42");
        assertThat(path.decodedPath()).isEqualTo("/v1/customers/42");
    }

    @Test
    void aBareProviderCallTargetsTheRoot() {
        assertThat(parse("/gateway/example").decodedPath()).isEqualTo("/");
    }

    @Test
    void percentEncodedCharactersAreAcceptedAndDecodedForAuthorization() {
        var path = parse("/gateway/example/v1/customers/john%20doe");
        assertThat(path.rawPath()).isEqualTo("/v1/customers/john%20doe");
        assertThat(path.decodedPath()).isEqualTo("/v1/customers/john doe");
    }

    @Test
    void multiByteSequencesDecodeAsUtf8() {
        assertThat(parse("/gateway/example/v1/caf%C3%A9").decodedPath()).isEqualTo("/v1/café");
    }

    @Test
    void authorizationSeesThroughEncodingUsedToDisguiseARoute() {
        // "/v1/%61dmin" reaches /v1/admin upstream, so the allowlist must be matched on the decoded form.
        assertThat(parse("/gateway/example/v1/%61dmin").decodedPath()).isEqualTo("/v1/admin");
    }

    @Test
    void encodedSeparatorsAreRefused() {
        assertThatThrownBy(() -> parse("/gateway/example/v1/a%2F..%2Fadmin"))
                .isInstanceOf(GatewayController.Denied.class);
        assertThatThrownBy(() -> parse("/gateway/example/v1/a%5Cadmin")).isInstanceOf(GatewayController.Denied.class);
    }

    @Test
    void traversalSegmentsAreRefused() {
        assertThatThrownBy(() -> parse("/gateway/example/v1/../admin")).isInstanceOf(GatewayController.Denied.class);
        assertThatThrownBy(() -> parse("/gateway/example/v1/./admin")).isInstanceOf(GatewayController.Denied.class);
        assertThatThrownBy(() -> parse("/gateway/example/v1/%2e%2e/admin"))
                .isInstanceOf(GatewayController.Denied.class);
    }

    @Test
    void emptySegmentsAndControlCharactersAreRefused() {
        assertThatThrownBy(() -> parse("/gateway/example//v1")).isInstanceOf(GatewayController.Denied.class);
        assertThatThrownBy(() -> parse("/gateway/example/v1/%00")).isInstanceOf(GatewayController.Denied.class);
        assertThatThrownBy(() -> parse("/gateway/example/v1/%0d%0aX-Injected:%20yes"))
                .isInstanceOf(GatewayController.Denied.class);
    }

    @Test
    void uriTemplateDelimitersAreRefused() {
        assertThatThrownBy(() -> parse("/gateway/example/v1/{id}")).isInstanceOf(GatewayController.Denied.class);
    }

    @Test
    void malformedEscapesAreRefused() {
        assertThatThrownBy(() -> parse("/gateway/example/v1/%zz")).isInstanceOf(GatewayController.Denied.class);
        assertThatThrownBy(() -> parse("/gateway/example/v1/%4")).isInstanceOf(GatewayController.Denied.class);
    }

    @Test
    void aSlugThatDoesNotMatchTheRawUriIsRefused() {
        assertThatThrownBy(() -> GatewayPath.parse("/gateway/%65xample/v1", "example", null))
                .isInstanceOf(GatewayController.Denied.class);
    }

    @Test
    void theTargetKeepsTheOriginalEncodingAndAppendsToTheBasePath() {
        var path = GatewayPath.parse("/gateway/example/v1/john%20doe", "example", "page=2&q=a%20b");
        assertThat(path.toTargetUri("https://api.example.com/base").toString())
                .isEqualTo("https://api.example.com/base/v1/john%20doe?page=2&q=a%20b");
    }

    @Test
    void aTrailingSlashOnTheBaseUrlDoesNotCreateAnEmptyPathSegment() {
        var path = GatewayPath.parse("/gateway/example/movie/popular", "example", null);
        assertThat(path.toTargetUri("https://api.themoviedb.org/3/").toString())
                .isEqualTo("https://api.themoviedb.org/3/movie/popular");
    }

    @Test
    void theTargetIsBuiltWithoutABasePath() {
        var path = GatewayPath.parse("/gateway/example/v1/items", "example", null);
        assertThat(path.toTargetUri("https://api.example.com").toString())
                .isEqualTo("https://api.example.com/v1/items");
    }

    @Test
    void aQueryStringCannotSmuggleControlCharacters() {
        assertThatThrownBy(() -> GatewayPath.parse("/gateway/example/v1", "example", "q=a b"))
                .isInstanceOf(GatewayController.Denied.class);
    }
}
