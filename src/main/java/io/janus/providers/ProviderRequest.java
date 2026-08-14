package io.janus.providers;

import jakarta.validation.constraints.*;

import io.janus.credentials.*;

/**
 * What an administrator may state about a destination.
 *
 * <p>The traffic fields are boxed because omitting them is meaningful: an unstated policy takes the
 * documented default rather than zero. They are normalised here, once, so no caller has to.
 *
 * @param cacheEnabled omitted means true: Janus reuses whatever the upstream says is reusable
 * @param cacheTtlSeconds freshness to assume when the upstream states none; 0 leaves it to the upstream
 * @param rateLimitPerMinute outbound ceiling for this destination, all callers together; 0 is no ceiling
 * @param rateLimitBurst how much of that allowance may be spent at once; 0 derives a tenth of it
 */
public record ProviderRequest(
        // Displayed, and carried into the body of the expiry mail. A control character in a name is
        // either a mistake or a header injection; neither has a use here.
        @NotBlank @Size(max = 120) @Pattern(regexp = "[^\\p{Cntrl}]*", message = "must not contain control characters")
                String name,
        @NotBlank
                @Pattern(
                        regexp = "[a-z0-9][a-z0-9-]{1,78}[a-z0-9]",
                        message = "must be lowercase letters, digits, and hyphens")
                String slug,
        @NotBlank @Size(max = 500) String baseUrl,
        boolean enabled,
        Boolean cacheEnabled,
        @Min(0) @Max(86400) Integer cacheTtlSeconds,
        @Min(0) @Max(1000000) Integer rateLimitPerMinute,
        @Min(0) @Max(100000) Integer rateLimitBurst,
        @NotNull AuthType authType,
        @Size(max = 100) String headerName,
        @Size(max = 100) String queryParameter,
        @Size(max = 500) String tokenUrl,
        @Size(max = 500) String tokenScopes,
        TokenClientAuth tokenClientAuth,
        SignatureAlgorithm signatureAlgorithm,
        @Size(max = SignatureTemplate.MAX_LENGTH) String signatureTemplate,
        SignatureEncoding signatureEncoding,
        @Size(max = 100) String signatureHeader,
        @Size(max = 100) String signatureParameter,
        @Size(max = 100) String timestampHeader,
        @Size(max = 100) String timestampParameter,
        // Refused outright unless the deployment offers it; see DestinationValidator. Boxed like the
        // traffic fields and for the same reason: a caller written before local networks were
        // addressable states nothing here, and an unstated answer is no rather than a refused
        // request. Last in the record so those callers still compile.
        Boolean allowPrivateDestination,
        // Boxed and last for the same reason as the field above: a caller written before this existed
        // states nothing, and an unstated answer is no.
        Boolean normalizeJson,
        // Element names, or dotted paths, separated by commas. Bounded in character as well as in
        // length: this is copied into no query and no address, but a field that accepts anything is
        // a field somebody eventually stores a document in.
        @Size(max = 1000)
                @Pattern(
                        regexp = "[\\p{L}\\p{N}_.:, -]*",
                        message = "must be element names or dotted paths, separated by commas")
                String jsonArrayPaths,
        // The account connection, set beside whatever the application itself presents. Last in the
        // record for the same reason as the fields above: a caller written before an API could offer
        // two identities states nothing here, and an unstated answer is "it offers one".
        @Size(max = 500) String connectionAuthorizationUrl,
        @Size(max = 500) String connectionTokenUrl,
        @Size(max = 500) String connectionScopes,
        TokenClientAuth connectionClientAuth) {

    public ProviderRequest {
        name = name == null ? null : name.trim();
        allowPrivateDestination = allowPrivateDestination != null && allowPrivateDestination;
        normalizeJson = normalizeJson != null && normalizeJson;
    }

    /** Compatibility overload for callers written before consent and signing were offered. */
    public ProviderRequest(
            String name,
            String slug,
            String baseUrl,
            boolean enabled,
            Boolean cacheEnabled,
            Integer cacheTtlSeconds,
            Integer rateLimitPerMinute,
            Integer rateLimitBurst,
            AuthType authType,
            String headerName,
            String queryParameter,
            String tokenUrl,
            String tokenScopes,
            TokenClientAuth tokenClientAuth) {
        this(
                name,
                slug,
                baseUrl,
                enabled,
                cacheEnabled,
                cacheTtlSeconds,
                rateLimitPerMinute,
                rateLimitBurst,
                authType,
                headerName,
                queryParameter,
                tokenUrl,
                tokenScopes,
                tokenClientAuth,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                null,
                null,
                null,
                null,
                null);
    }

    /** Compatibility overload for clients written before authentication moved onto the API. */
    public ProviderRequest(
            String name,
            String slug,
            String baseUrl,
            boolean enabled,
            Boolean cacheEnabled,
            Integer cacheTtlSeconds,
            Integer rateLimitPerMinute,
            Integer rateLimitBurst) {
        this(
                name,
                slug,
                baseUrl,
                enabled,
                cacheEnabled,
                cacheTtlSeconds,
                rateLimitPerMinute,
                rateLimitBurst,
                AuthType.NONE,
                null,
                null,
                null,
                null,
                null);
    }

    public Provider.Normalization normalization() {
        return new Provider.Normalization(normalizeJson != null && normalizeJson, jsonArrayPaths);
    }

    public Provider.TrafficPolicy trafficPolicy() {
        return new Provider.TrafficPolicy(
                cacheEnabled == null || cacheEnabled,
                orZero(cacheTtlSeconds),
                orZero(rateLimitPerMinute),
                orZero(rateLimitBurst));
    }

    public Provider.Auth auth() {
        return new Provider.Auth(authType, headerName, queryParameter, tokenUrl, tokenScopes, tokenClientAuth, signature());
    }

    /** What the API offers an account holder, or a connection offering nothing when it offers none. */
    public Provider.Connection connection() {
        return new Provider.Connection(
                connectionAuthorizationUrl, connectionTokenUrl, connectionScopes, connectionClientAuth);
    }

    /** The signing recipe, or null when this strategy does not sign. */
    public SignatureSettings signature() {
        if (authType == null || !authType.signs()) return null;
        return new SignatureSettings(
                signatureAlgorithm,
                signatureTemplate == null || signatureTemplate.isBlank()
                        ? null
                        : new SignatureTemplate(signatureTemplate),
                signatureEncoding,
                signatureHeader,
                signatureParameter,
                timestampHeader,
                timestampParameter);
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
