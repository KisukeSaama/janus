package io.janus.credentials;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class UpstreamTokenCacheTest {
    private final UpstreamTokenCache cache = new UpstreamTokenCache();
    private final UUID credential = UUID.randomUUID();

    @Test
    void servesAHeldTokenBackUntilItIsWorthReplacing() {
        cache.store(credential, "upstream-token", 3600L);
        assertThat(cache.lookup(credential)).contains("upstream-token");
    }

    /**
     * The margin is the point: a token handed over in the last second of its life fails a call that
     * had no reason to fail, and providers are not precise to the second. It is renewed early rather
     * than used to the last moment.
     */
    @Test
    void aTokenIsGivenUpBeforeTheProvidersOwnDeadline() {
        assertThat(UpstreamTokenCache.usableSeconds(3600L)).isEqualTo(3600 - UpstreamTokenCache.SAFETY_MARGIN_SECONDS);
    }

    /** A provider announcing less than the margin is saying the token is nearly spent. */
    @Test
    void aVeryShortLivedTokenIsStillHeldForAMomentRatherThanNotAtAll() {
        assertThat(UpstreamTokenCache.usableSeconds(UpstreamTokenCache.SAFETY_MARGIN_SECONDS))
                .isEqualTo(1);
        assertThat(UpstreamTokenCache.usableSeconds(0L)).isEqualTo(1);
    }

    @Test
    void aProviderThatStatesNoLifetimeGetsAConservativeOne() {
        cache.store(credential, "unstated", null);
        assertThat(cache.lookup(credential)).contains("unstated");
        assertThat(UpstreamTokenCache.usableSeconds(null))
                .isEqualTo(UpstreamTokenCache.ASSUMED_LIFETIME_SECONDS - UpstreamTokenCache.SAFETY_MARGIN_SECONDS);
    }

    @Test
    void anUnknownCredentialHoldsNothing() {
        assertThat(cache.lookup(UUID.randomUUID())).isEmpty();
        assertThat(cache.recentFailure(UUID.randomUUID())).isEmpty();
    }

    /**
     * Without this, a wrong client secret calls the provider's token endpoint once per proxied
     * request — which is how a typo becomes an outbound flood and, at some providers, a block.
     */
    @Test
    void aRefusalIsRememberedSoItIsNotRetriedOnEveryRequest() {
        cache.storeFailure(credential, 401);

        assertThat(cache.recentFailure(credential)).hasValue(401);
        assertThat(cache.lookup(credential)).isEmpty();
    }

    /** A rotated secret leaves behind a token the provider may honour for another hour. */
    @Test
    void invalidatingACredentialDropsWhateverIsHeldForIt() {
        cache.store(credential, "issued-with-the-old-secret", 3600L);
        cache.invalidate(credential);
        assertThat(cache.lookup(credential)).isEmpty();
    }

    @Test
    void aStoredTokenReplacesARememberedFailure() {
        cache.storeFailure(credential, 401);
        cache.store(credential, "it-works-now", 3600L);

        assertThat(cache.recentFailure(credential)).isEmpty();
        assertThat(cache.lookup(credential)).contains("it-works-now");
    }
}
