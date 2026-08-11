package io.janus.providers;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import io.janus.accounts.Account;
import io.janus.credentials.*;

/**
 * Fixed upstream destination. Gateway callers never supply a URL; they name a provider slug.
 *
 * <p>The traffic policy lives here rather than in a service because its one rule — a burst is
 * meaningless without an allowance to burst against — must hold for every path that writes it.
 *
 * <p>A provider is a deployment-wide catalogue entry. Administrators define it once; each account
 * activates it with a separate credential.
 */
@Entity
@Table(name = "providers", uniqueConstraints = @UniqueConstraint(name = "uq_provider_slug", columnNames = "slug"))
public class Provider {
    @Id
    private UUID id;

    /** Only retained for source compatibility with pre-catalogue unit fixtures; never persisted. */
    @Transient
    private Account legacyOwner;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 80)
    private String slug;

    @Column(name = "base_url", nullable = false, length = 500)
    private String baseUrl;

    @Column(nullable = false)
    private boolean enabled = true;

    /** Whether Janus may reuse a response for this destination at all. */
    @Column(name = "cache_enabled", nullable = false)
    private boolean cacheEnabled = true;

    /** Freshness applied when the upstream states none. Zero leaves the decision entirely to it. */
    @Column(name = "cache_ttl_seconds", nullable = false)
    private int cacheTtlSeconds;

    /** Ceiling on outbound calls to this destination, all callers combined. Zero is no ceiling. */
    @Column(name = "rate_limit_per_minute", nullable = false)
    private int rateLimitPerMinute;

    /** How much of that allowance may be spent at once. Zero derives a tenth of the allowance. */
    @Column(name = "rate_limit_burst", nullable = false)
    private int rateLimitBurst;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false, length = 32)
    private AuthType authType;

    /**
     * The header a key travels in — and, for a signed request, the header identifying who signed it.
     */
    @Column(name = "header_name", length = 100)
    private String headerName;

    @Column(name = "query_parameter", length = 100)
    private String queryParameter;

    @Column(name = "token_url", length = 500)
    private String tokenUrl;

    @Column(name = "token_scopes", length = 500)
    private String tokenScopes;

    @Enumerated(EnumType.STRING)
    @Column(name = "token_client_auth", length = 16)
    private TokenClientAuth tokenClientAuth;

    /** Where a person is sent to agree, for an authorisation-code destination. */
    @Column(name = "authorization_url", length = 500)
    private String authorizationUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "signature_algorithm", length = 16)
    private SignatureAlgorithm signatureAlgorithm;

    @Column(name = "signature_template", length = 500)
    private String signatureTemplate;

    @Enumerated(EnumType.STRING)
    @Column(name = "signature_encoding", length = 16)
    private SignatureEncoding signatureEncoding;

    @Column(name = "signature_header", length = 100)
    private String signatureHeader;

    @Column(name = "signature_parameter", length = 100)
    private String signatureParameter;

    @Column(name = "timestamp_header", length = 100)
    private String timestampHeader;

    @Column(name = "timestamp_parameter", length = 100)
    private String timestampParameter;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** For Hibernate only. */
    protected Provider() {}

    public Provider(String name, String slug, String baseUrl, boolean enabled, TrafficPolicy traffic, Auth auth) {
        this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        describe(name, slug, baseUrl, enabled);
        applyTrafficPolicy(traffic);
        applyAuth(auth);
    }

    /** Compatibility constructor for tests and migration-era callers. */
    public Provider(Account owner, String name, String slug, String baseUrl, boolean enabled, TrafficPolicy traffic) {
        this(name, slug, baseUrl, enabled, traffic, Auth.none());
        this.legacyOwner = owner;
    }

    /**
     * What Janus handles on the callers' behalf for this destination.
     *
     * @param cacheEnabled whether a response from here may be reused at all
     * @param ttlSeconds freshness assumed when the upstream states none; zero defers to it
     * @param ratePerMinute outbound ceiling, every caller combined; zero is no ceiling
     * @param burst how much of that allowance may be spent at once; zero derives a tenth of it
     */
    public record TrafficPolicy(boolean cacheEnabled, int ttlSeconds, int ratePerMinute, int burst) {
        public TrafficPolicy {
            if (burst > 0 && ratePerMinute == 0)
                throw new IllegalArgumentException("A burst is only meaningful with a per-minute rate limit");
        }
    }

    /**
     * The authentication contract, which belongs to the API rather than to any one account: every
     * caller of a destination presents in the same way, and only the value differs between them.
     */
    public record Auth(
            AuthType type,
            String headerName,
            String queryParameter,
            String tokenUrl,
            String tokenScopes,
            TokenClientAuth tokenClientAuth,
            String authorizationUrl,
            SignatureSettings signature) {

        /** For the strategies that were the whole vocabulary before consent and signing were added. */
        public Auth(
                AuthType type,
                String headerName,
                String queryParameter,
                String tokenUrl,
                String tokenScopes,
                TokenClientAuth tokenClientAuth) {
            this(type, headerName, queryParameter, tokenUrl, tokenScopes, tokenClientAuth, null, null);
        }

        public static Auth none() {
            return new Auth(AuthType.NONE, null, null, null, null, null);
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void describe(String name, String slug, String baseUrl, boolean enabled) {
        this.name = name;
        this.slug = slug;
        this.baseUrl = baseUrl;
        this.enabled = enabled;
    }

    public void applyTrafficPolicy(TrafficPolicy traffic) {
        this.cacheEnabled = traffic.cacheEnabled();
        this.cacheTtlSeconds = traffic.ttlSeconds();
        this.rateLimitPerMinute = traffic.ratePerMinute();
        this.rateLimitBurst = traffic.burst();
    }

    /**
     * Applies the contract, clearing every setting that belongs to another strategy. Stated once here
     * and again in the database's constraints, so a row written by anything else means what it says.
     */
    public void applyAuth(Auth auth) {
        var type = auth.type();
        this.authType = type;
        this.headerName = type == AuthType.API_KEY_HEADER || type == AuthType.HMAC_SIGNATURE ? auth.headerName() : null;
        this.queryParameter = type == AuthType.API_KEY_QUERY ? auth.queryParameter() : null;
        this.tokenUrl = type.exchanged() ? auth.tokenUrl() : null;
        this.tokenScopes = type.exchanged() ? blankToNull(auth.tokenScopes()) : null;
        this.tokenClientAuth = type.exchanged()
                ? java.util.Objects.requireNonNullElse(auth.tokenClientAuth(), TokenClientAuth.BASIC)
                : null;
        this.authorizationUrl = type.consented() ? auth.authorizationUrl() : null;
        applySignature(type, auth.signature());
    }

    private void applySignature(AuthType type, SignatureSettings signature) {
        boolean applies = type.signs() && signature != null;
        this.signatureAlgorithm = applies ? signature.algorithm() : null;
        this.signatureTemplate = applies ? signature.template().pattern() : null;
        this.signatureEncoding = applies ? signature.encoding() : null;
        this.signatureHeader = applies ? signature.signatureHeader() : null;
        this.signatureParameter = applies ? signature.signatureParameter() : null;
        this.timestampHeader = applies ? signature.timestampHeader() : null;
        this.timestampParameter = applies ? signature.timestampParameter() : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Hands the destination to somebody else. The slug moves with it, so the caller must invalidate
     * what was addressed by it — {@code TrafficPolicyRegistry.forgetProvider} — as for any edit.
     */
    /** @deprecated APIs are global; only kept for old fixtures. */
    @Deprecated
    public Account getOwner() {
        return legacyOwner;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isCacheEnabled() {
        return cacheEnabled;
    }

    public int getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public int getRateLimitPerMinute() {
        return rateLimitPerMinute;
    }

    public int getRateLimitBurst() {
        return rateLimitBurst;
    }

    public AuthType getAuthType() {
        return authType;
    }

    public String getHeaderName() {
        return headerName;
    }

    public String getQueryParameter() {
        return queryParameter;
    }

    public String getTokenUrl() {
        return tokenUrl;
    }

    public String getTokenScopes() {
        return tokenScopes;
    }

    public TokenClientAuth getTokenClientAuth() {
        return tokenClientAuth;
    }

    public String getAuthorizationUrl() {
        return authorizationUrl;
    }

    public SignatureAlgorithm getSignatureAlgorithm() {
        return signatureAlgorithm;
    }

    /** The recipe this destination expects requests to be signed with, or null when it expects none. */
    public SignatureSettings signatureSettings() {
        if (!authType.signs() || signatureTemplate == null) return null;
        return new SignatureSettings(
                signatureAlgorithm,
                new SignatureTemplate(signatureTemplate),
                signatureEncoding,
                signatureHeader,
                signatureParameter,
                timestampHeader,
                timestampParameter);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
