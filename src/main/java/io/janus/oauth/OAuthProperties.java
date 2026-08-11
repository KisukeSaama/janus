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
 */
@ConfigurationProperties("janus.oauth")
public record OAuthProperties(
        @DefaultValue("15m") Duration accessTokenTtl,
        @DefaultValue("30d") Duration refreshTokenTtl,
        @DefaultValue("true") boolean refreshEnabled,
        @DefaultValue("10000") int maxActiveTokens) {

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
    }
}
