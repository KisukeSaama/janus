package io.janus.providers;

import java.time.Instant;
import java.util.UUID;

import io.janus.credentials.*;

/**
 * A destination as the console sees it.
 *
 * @param allowPrivateDestination whether this destination is registered as living on the local
 *     network
 * @param normalizeJson whether callers receive JSON whatever this destination answers in
 * @param jsonArrayPaths elements that must always be arrays once converted, comma-separated
 * @param connectionAuthorizationUrl the account connection this API offers, null throughout when it
 *     offers none
 */
public record ProviderResponse(
        UUID id,
        String name,
        String slug,
        String baseUrl,
        boolean enabled,
        boolean allowPrivateDestination,
        boolean cacheEnabled,
        int cacheTtlSeconds,
        boolean normalizeJson,
        String jsonArrayPaths,
        int rateLimitPerMinute,
        int rateLimitBurst,
        AuthType authType,
        String headerName,
        String queryParameter,
        String tokenUrl,
        String tokenScopes,
        TokenClientAuth tokenClientAuth,
        String clientIdHeader,
        String connectionAuthorizationUrl,
        String connectionTokenUrl,
        String connectionScopes,
        TokenClientAuth connectionClientAuth,
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
        var connection = provider.connection();
        return new ProviderResponse(
                provider.getId(),
                provider.getName(),
                provider.getSlug(),
                provider.getBaseUrl(),
                provider.isEnabled(),
                provider.isAllowPrivateDestination(),
                provider.isCacheEnabled(),
                provider.getCacheTtlSeconds(),
                provider.isNormalizeJson(),
                provider.getJsonArrayPaths(),
                provider.getRateLimitPerMinute(),
                provider.getRateLimitBurst(),
                provider.getAuthType(),
                provider.getHeaderName(),
                provider.getQueryParameter(),
                provider.getTokenUrl(),
                provider.getTokenScopes(),
                provider.getTokenClientAuth(),
                provider.getClientIdHeader(),
                connection.authorizationUrl(),
                connection.tokenUrl(),
                connection.scopes(),
                connection.clientAuth(),
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
