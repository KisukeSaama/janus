package io.janus.providers;

import java.time.Instant;
import java.util.UUID;

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
        Instant createdAt,
        Instant updatedAt) {

    public static ProviderResponse of(Provider provider) {
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
                provider.getCreatedAt(),
                provider.getUpdatedAt());
    }
}
