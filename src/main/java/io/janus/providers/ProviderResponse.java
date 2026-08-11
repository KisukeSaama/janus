package io.janus.providers;

import java.time.Instant;
import java.util.UUID;

import io.janus.credentials.AuthType;
import io.janus.credentials.TokenClientAuth;

/** A destination as the console sees it. */
public record ProviderResponse(
        UUID id,
        String name,
        String slug,
        String baseUrl,
        boolean enabled,
        boolean cacheEnabled,
        int cacheTtlSeconds,
        int rateLimitPerMinute,
        int rateLimitBurst,
        AuthType authType,
        String headerName,
        String queryParameter,
        String tokenUrl,
        String tokenScopes,
        TokenClientAuth tokenClientAuth,
        boolean activated,
        Instant createdAt,
        Instant updatedAt) {

    public static ProviderResponse of(Provider provider) {
        return of(provider, false);
    }

    public static ProviderResponse of(Provider provider, boolean activated) {
        return new ProviderResponse(
                provider.getId(),
                provider.getName(),
                provider.getSlug(),
                provider.getBaseUrl(),
                provider.isEnabled(),
                provider.isCacheEnabled(),
                provider.getCacheTtlSeconds(),
                provider.getRateLimitPerMinute(),
                provider.getRateLimitBurst(),
                provider.getAuthType(),
                provider.getHeaderName(),
                provider.getQueryParameter(),
                provider.getTokenUrl(),
                provider.getTokenScopes(),
                provider.getTokenClientAuth(),
                activated,
                provider.getCreatedAt(),
                provider.getUpdatedAt());
    }
}
