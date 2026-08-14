package io.janus.gateway;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * The reduction that decides whether anything is ever learned.
 *
 * <p>Without it every call carries a path never seen before, every call pays the replay, and the
 * memory fills with one entry per playlist. These are the shapes real APIs actually serve.
 */
class RouteTemplateTest {

    @Test
    void keepsAPathThatNamesAnEndpointRatherThanAThing() {
        assertThat(RouteTemplate.of("/me/playlists")).isEqualTo("/me/playlists");
        assertThat(RouteTemplate.of("/search")).isEqualTo("/search");
    }

    @Test
    void reducesTheIdentifiersApisActuallyIssue() {
        // Spotify: base62.
        assertThat(RouteTemplate.of("/playlists/3cEYpjA9oz9GiPac4AsH4n")).isEqualTo("/playlists/*");
        // Discord: snowflakes.
        assertThat(RouteTemplate.of("/channels/1071234567890123456/messages"))
                .isEqualTo("/channels/*/messages");
        // Plain numeric, and UUIDs.
        assertThat(RouteTemplate.of("/orders/42")).isEqualTo("/orders/*");
        assertThat(RouteTemplate.of("/users/f81d4fae-7dec-11d0-a765-00a0c91e6bf6"))
                .isEqualTo("/users/*");
    }

    /**
     * The one common segment the digit rule would otherwise swallow. A version names the endpoint;
     * it is not a value the endpoint varies by.
     */
    @Test
    void keepsAVersionSegment() {
        assertThat(RouteTemplate.of("/v1/tracks")).isEqualTo("/v1/tracks");
        assertThat(RouteTemplate.of("/v10/guilds/1071234567890123456")).isEqualTo("/v10/guilds/*");
    }

    @Test
    void twoCallsToOneEndpointShareATemplate() {
        assertThat(RouteTemplate.of("/playlists/3cEYpjA9oz9GiPac4AsH4n/tracks"))
                .isEqualTo(RouteTemplate.of("/playlists/6Qs4SXO9dwPj5GKvFOSXuI/tracks"));
    }

    @Test
    void twoDifferentEndpointsDoNotCollapseIntoOne() {
        assertThat(RouteTemplate.of("/me/playlists")).isNotEqualTo(RouteTemplate.of("/me/albums"));
    }

    @Test
    void treatsTheRootAndAnEmptyPathAlike() {
        assertThat(RouteTemplate.of("/")).isEqualTo("/");
        assertThat(RouteTemplate.of("")).isEqualTo("/");
        assertThat(RouteTemplate.of(null)).isEqualTo("/");
    }

    /** A caller cannot make one entry arbitrarily long by nesting. */
    @Test
    void stopsAfterTheSegmentsThatSayAnything() {
        String deep = RouteTemplate.of("/a/b/c/d/e/f/g/h/i/j/k");
        assertThat(deep).endsWith("…");
        assertThat(deep.split("/")).hasSizeLessThan(11);
    }

    @Test
    void ignoresCaseAndTrailingSeparators() {
        assertThat(RouteTemplate.of("/Me/Playlists")).isEqualTo(RouteTemplate.of("/me/playlists/"));
    }
}
