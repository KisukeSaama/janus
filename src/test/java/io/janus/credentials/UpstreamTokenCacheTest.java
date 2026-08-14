package io.janus.credentials;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class UpstreamTokenCacheTest {
    private final UpstreamTokenCache cache = new UpstreamTokenCache();
    private final UUID credential = UUID.randomUUID();

    @Test
    void servesAHeldTokenBackUntilItIsWorthReplacing() {
        cache.store(credential, Identity.APP, "upstream-token", 3600L);
        assertThat(cache.lookup(credential, Identity.APP)).contains("upstream-token");
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
        cache.store(credential, Identity.APP, "unstated", null);
        assertThat(cache.lookup(credential, Identity.APP)).contains("unstated");
        assertThat(UpstreamTokenCache.usableSeconds(null))
                .isEqualTo(UpstreamTokenCache.ASSUMED_LIFETIME_SECONDS - UpstreamTokenCache.SAFETY_MARGIN_SECONDS);
    }

    @Test
    void anUnknownCredentialHoldsNothing() {
        assertThat(cache.lookup(UUID.randomUUID(), Identity.APP)).isEmpty();
        assertThat(cache.recentFailure(UUID.randomUUID(), Identity.APP)).isEmpty();
    }

    /**
     * Without this, a wrong client secret calls the provider's token endpoint once per proxied
     * request — which is how a typo becomes an outbound flood and, at some providers, a block.
     */
    @Test
    void aRefusalIsRememberedSoItIsNotRetriedOnEveryRequest() {
        cache.storeFailure(credential, Identity.APP, 401);

        assertThat(cache.recentFailure(credential, Identity.APP)).hasValue(401);
        assertThat(cache.lookup(credential, Identity.APP)).isEmpty();
    }

    /** A rotated secret leaves behind a token the provider may honour for another hour. */
    @Test
    void invalidatingACredentialDropsWhateverIsHeldForIt() {
        cache.store(credential, Identity.APP, "issued-with-the-old-secret", 3600L);
        cache.invalidate(credential);
        assertThat(cache.lookup(credential, Identity.APP)).isEmpty();
    }

    @Test
    void aStoredTokenReplacesARememberedFailure() {
        cache.storeFailure(credential, Identity.APP, 401);
        cache.store(credential, Identity.APP, "it-works-now", 3600L);

        assertThat(cache.recentFailure(credential, Identity.APP)).isEmpty();
        assertThat(cache.lookup(credential, Identity.APP)).contains("it-works-now");
    }

    /**
     * The whole reason the key carries an identity. One credential holds both tokens, obtained from
     * one client id at one token endpoint; keyed by credential alone the second would evict the first,
     * and a call meant to go out as the application would go out as whoever connected their account.
     */
    @Test
    void theTwoIdentitiesTokensDoNotDisplaceEachOther() {
        cache.store(credential, Identity.APP, "the-applications-token", 3600L);
        cache.store(credential, Identity.ACCOUNT, "somebodys-own-token", 3600L);

        assertThat(cache.lookup(credential, Identity.APP)).contains("the-applications-token");
        assertThat(cache.lookup(credential, Identity.ACCOUNT)).contains("somebodys-own-token");
    }

    /** A refusal for one identity says nothing about the other, and must not silence it. */
    @Test
    void aRefusalIsRememberedAgainstOneIdentityOnly() {
        cache.storeFailure(credential, Identity.ACCOUNT, 401);
        cache.store(credential, Identity.APP, "still-good", 3600L);

        assertThat(cache.recentFailure(credential, Identity.APP)).isEmpty();
        assertThat(cache.lookup(credential, Identity.APP)).contains("still-good");
    }

    /** What the gateway does on a refusal: drop the one that was refused, keep the other. */
    @Test
    void invalidatingOneIdentityLeavesTheOther() {
        cache.store(credential, Identity.APP, "the-applications-token", 3600L);
        cache.store(credential, Identity.ACCOUNT, "somebodys-own-token", 3600L);

        cache.invalidate(credential, Identity.APP);

        assertThat(cache.lookup(credential, Identity.APP)).isEmpty();
        assertThat(cache.lookup(credential, Identity.ACCOUNT)).contains("somebodys-own-token");
    }

    /** A rotated secret invalidates both: it is the client both of them were obtained with. */
    @Test
    void invalidatingTheCredentialDropsBothIdentities() {
        cache.store(credential, Identity.APP, "one", 3600L);
        cache.store(credential, Identity.ACCOUNT, "two", 3600L);

        cache.invalidate(credential);

        assertThat(cache.lookup(credential, Identity.APP)).isEmpty();
        assertThat(cache.lookup(credential, Identity.ACCOUNT)).isEmpty();
    }
}
