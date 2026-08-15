package io.janus.oauth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How long the two tokens live.
 *
 * <p>The access token is short because it travels on every call and is held wherever the caller
 * happens to be, including a browser tab. The refresh token is long because it is the thing a client
 * stores once and is expected not to think about again; it rotates on every use, so a long life is
 * not a long window of exposure.
 *
 * @param accessTokenTtl how long an issued bearer token is honoured
 * @param refreshTokenTtl how long a client may keep coming back without presenting its secret again
 * @param refreshEnabled whether the exchange hands out refresh tokens at all; a deployment where
 *     every caller is a server holding its own secret may prefer that it does not
 * @param maxActiveTokens how many issued bearer tokens the process holds at once, across everybody
 * @param maxActiveTokensPerApplication how many of those one application may hold; 0 derives a tenth
 *     of the figure above. A ceiling of its own so that one caller asking for tokens in a loop is
 *     bounded by its own share rather than by evicting everybody else's.
 */
@ConfigurationProperties("janus.oauth")
public record OAuthProperties(
        @DefaultValue("15m") Duration accessTokenTtl,
        @DefaultValue("30d") Duration refreshTokenTtl,
        @DefaultValue("true") boolean refreshEnabled,
        @DefaultValue("10000") int maxActiveTokens,
        @DefaultValue("0") int maxActiveTokensPerApplication) {

    public OAuthProperties {
        if (accessTokenTtl.isNegative() || accessTokenTtl.isZero())
            throw new IllegalArgumentException("janus.oauth.access-token-ttl must be positive");
        if (refreshTokenTtl.isNegative() || refreshTokenTtl.isZero())
            throw new IllegalArgumentException("janus.oauth.refresh-token-ttl must be positive");
        // A refresh token that outlives nothing is a bearer token with extra steps, and one that is
        // shorter than the access token it renews can never be used.
        if (refreshTokenTtl.compareTo(accessTokenTtl) < 0)
            throw new IllegalArgumentException(
                    "janus.oauth.refresh-token-ttl must not be shorter than the access token it renews");
        if (maxActiveTokens < 1) throw new IllegalArgumentException("janus.oauth.max-active-tokens must be positive");
        if (maxActiveTokensPerApplication < 0)
            throw new IllegalArgumentException("janus.oauth.max-active-tokens-per-application must not be negative");
    }

    /**
     * The per-application ceiling actually in force: a tenth of the store when nothing was stated.
     *
     * <p>With a floor, because one application legitimately holds several tokens at once — several
     * instances of the same service, a client renewing before its old token expires — and a derived
     * ceiling below that would evict a caller's own working tokens rather than protecting anybody
     * from it. Where the floor exceeds the store, the store's own bound governs, which is the right
     * answer for a deployment configured that small.
     */
    public int applicationTokenCeiling() {
        return maxActiveTokensPerApplication > 0 ? maxActiveTokensPerApplication : Math.max(8, maxActiveTokens / 10);
    }
}
