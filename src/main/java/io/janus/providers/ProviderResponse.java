package io.janus.providers;

import java.time.Instant;
import java.util.UUID;

import io.janus.credentials.*;

/** A destination as the console sees it. */
public record ProviderResponse(
        UUID id,
        String name,
        String slug,
        String baseUrl,
        boolean enabled,
        /** Whether this destination is registered as living on the local network. */
        boolean allowPrivateDestination,
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
        String authorizationUrl,
        SignatureAlgorithm signatureAlgorithm,
        String signatureTemplate,
        SignatureEncoding signatureEncoding,
        String signatureHeader,
        String signatureParameter,
        String timestampHeader,
        String timestampParameter,
        boolean activated,
        Instant createdAt,
        Instant updatedAt) {

    public static ProviderResponse of(Provider provider) {
        return of(provider, false);
    }

    public static ProviderResponse of(Provider provider, boolean activated) {
        var signature = provider.signatureSettings();
        return new ProviderResponse(
                provider.getId(),
                provider.getName(),
                provider.getSlug(),
                provider.getBaseUrl(),
                provider.isEnabled(),
                provider.isAllowPrivateDestination(),
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
                provider.getAuthorizationUrl(),
                provider.getSignatureAlgorithm(),
                signature == null ? null : signature.template().pattern(),
                signature == null ? null : signature.encoding(),
                signature == null ? null : signature.signatureHeader(),
                signature == null ? null : signature.signatureParameter(),
                signature == null ? null : signature.timestampHeader(),
                signature == null ? null : signature.timestampParameter(),
                activated,
                provider.getCreatedAt(),
                provider.getUpdatedAt());
    }
}
