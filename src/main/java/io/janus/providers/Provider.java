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

    /**
     * Whether this destination may resolve to an address on the local network. False everywhere
     * unless an administrator says otherwise, and never enough to reach loopback or link-local —
     * see {@link DestinationValidator}.
     */
    @Column(name = "allow_private_destination", nullable = false)
    private boolean allowPrivateDestination;

    /** Whether Janus may reuse a response for this destination at all. */
    @Column(name = "cache_enabled", nullable = false)
    private boolean cacheEnabled = true;

    /** Freshness applied when the upstream states none. Zero leaves the decision entirely to it. */
    @Column(name = "cache_ttl_seconds", nullable = false)
    private int cacheTtlSeconds;

    /**
     * Whether Janus restates this destination's responses as JSON.
     *
     * <p>Says nothing about which format to expect: the converter is chosen from the response's own
     * {@code Content-Type}, because an API answering XML on one route and JSON on another is ordinary
     * rather than exceptional.
     */
    @Column(name = "normalize_json", nullable = false)
    private boolean normalizeJson;

    /** Which elements must always come out as arrays; see {@code ArrayPaths}. */
    @Column(name = "json_array_paths", length = 1000)
    private String jsonArrayPaths;

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

    /**
     * The header this API wants the client id on, beside the token obtained with it. Null for the
     * great majority, which read the client from the token alone; Twitch is the one every deployment
     * meets, and it refuses a Helix call without {@code Client-Id} whatever the token says.
     *
     * <p>Only the name is here. The value is the left half of the stored secret, read from OpenBao at
     * call time like everything else.
     */
    @Column(name = "client_id_header", length = 100)
    private String clientIdHeader;

    /**
     * Where a person is sent to agree, when this destination lets an account holder connect theirs.
     * Null means it does not, and the three columns below are null with it.
     */
    @Column(name = "connection_authorization_url", length = 500)
    private String connectionAuthorizationUrl;

    @Column(name = "connection_token_url", length = 500)
    private String connectionTokenUrl;

    @Column(name = "connection_scopes", length = 500)
    private String connectionScopes;

    @Enumerated(EnumType.STRING)
    @Column(name = "connection_client_auth", length = 16)
    private TokenClientAuth connectionClientAuth;

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
        this(name, slug, baseUrl, enabled, false, traffic, auth);
    }

    public Provider(
            String name,
            String slug,
            String baseUrl,
            boolean enabled,
            TrafficPolicy traffic,
            Auth auth,
            Connection connection) {
        this(name, slug, baseUrl, enabled, false, traffic, auth, connection);
    }

    public Provider(
            String name,
            String slug,
            String baseUrl,
            boolean enabled,
            boolean allowPrivateDestination,
            TrafficPolicy traffic,
            Auth auth) {
        this(name, slug, baseUrl, enabled, allowPrivateDestination, traffic, auth, Connection.none());
    }

    public Provider(
            String name,
            String slug,
            String baseUrl,
            boolean enabled,
            boolean allowPrivateDestination,
            TrafficPolicy traffic,
            Auth auth,
            Connection connection) {
        this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        describe(name, slug, baseUrl, enabled, allowPrivateDestination);
        applyTrafficPolicy(traffic);
        applyAuth(auth);
        applyConnection(connection);
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
     * Whether callers of this destination receive JSON whatever it answers in.
     *
     * <p>Kept apart from {@link TrafficPolicy} rather than folded into it. That record is about how
     * much Janus may call a destination and how long an answer stays good; this is about what the
     * answer looks like, and the two are set by different people for different reasons — one from
     * what the upstream's quota allows, the other from what its callers can parse.
     *
     * @param enabled    whether responses are restated as JSON
     * @param arrayPaths elements that must always be arrays, comma-separated; see {@code ArrayPaths}
     */
    public record Normalization(boolean enabled, String arrayPaths) {

        public static Normalization none() {
            return new Normalization(false, null);
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
            SignatureSettings signature,
            String clientIdHeader) {

        /** For the strategies that were the whole vocabulary before signing was added. */
        public Auth(
                AuthType type,
                String headerName,
                String queryParameter,
                String tokenUrl,
                String tokenScopes,
                TokenClientAuth tokenClientAuth) {
            this(type, headerName, queryParameter, tokenUrl, tokenScopes, tokenClientAuth, null);
        }

        /** For callers written before an exchange could also name a header for its client id. */
        public Auth(
                AuthType type,
                String headerName,
                String queryParameter,
                String tokenUrl,
                String tokenScopes,
                TokenClientAuth tokenClientAuth,
                SignatureSettings signature) {
            this(type, headerName, queryParameter, tokenUrl, tokenScopes, tokenClientAuth, signature, null);
        }

        public static Auth none() {
            return new Auth(AuthType.NONE, null, null, null, null, null);
        }
    }

    /**
     * The second identity a destination may offer: the one belonging to whoever connects their
     * account, rather than to the application.
     *
     * <p>Deliberately not another {@link AuthType}. It was one, and that is what forced Spotify to be
     * registered twice — a destination has one auth type, so an API offering both identities could not
     * be one row. Here the two are orthogonal: any application identity may carry a connection beside
     * it, including none at all, and including a Discord bot token that has nothing to do with OAuth.
     *
     * @param authorizationUrl where the person signs in and agrees; not the token endpoint
     * @param tokenUrl         where their code, and later their refresh token, is exchanged
     * @param scopes           what they will be granting, space separated; null takes the client's own
     * @param clientAuth       how Janus proves who it is at that token endpoint
     */
    public record Connection(String authorizationUrl, String tokenUrl, String scopes, TokenClientAuth clientAuth) {

        /** The destination offers no account connection at all. */
        public static Connection none() {
            return new Connection(null, null, null, null);
        }

        public boolean offered() {
            return authorizationUrl != null && tokenUrl != null;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public final void describe(String name, String slug, String baseUrl, boolean enabled) {
        describe(name, slug, baseUrl, enabled, false);
    }

    public final void describe(
            String name, String slug, String baseUrl, boolean enabled, boolean allowPrivateDestination) {
        this.name = name;
        this.slug = slug;
        this.baseUrl = baseUrl;
        this.enabled = enabled;
        this.allowPrivateDestination = allowPrivateDestination;
    }

    /** Paths are only kept while normalisation is on, so a row never states a rule nothing reads. */
    public void applyNormalization(Normalization normalization) {
        this.normalizeJson = normalization.enabled();
        this.jsonArrayPaths = normalization.enabled() ? blankToNull(normalization.arrayPaths()) : null;
    }

    public final void applyTrafficPolicy(TrafficPolicy traffic) {
        this.cacheEnabled = traffic.cacheEnabled();
        this.cacheTtlSeconds = traffic.ttlSeconds();
        this.rateLimitPerMinute = traffic.ratePerMinute();
        this.rateLimitBurst = traffic.burst();
    }

    /**
     * Applies the contract, clearing every setting that belongs to another strategy. Stated once here
     * and again in the database's constraints, so a row written by anything else means what it says.
     */
    public final void applyAuth(Auth auth) {
        var type = auth.type();
        this.authType = type;
        this.headerName = type == AuthType.API_KEY_HEADER || type == AuthType.HMAC_SIGNATURE ? auth.headerName() : null;
        this.queryParameter = type == AuthType.API_KEY_QUERY ? auth.queryParameter() : null;
        this.tokenUrl = type.exchanged() ? auth.tokenUrl() : null;
        this.tokenScopes = type.exchanged() ? blankToNull(auth.tokenScopes()) : null;
        this.tokenClientAuth = type.exchanged()
                ? java.util.Objects.requireNonNullElse(auth.tokenClientAuth(), TokenClientAuth.BASIC)
                : null;
        this.clientIdHeader = type.exchanged() ? blankToNull(auth.clientIdHeader()) : null;
        applySignature(type, auth.signature());
    }

    /** Applied on its own, because a connection is independent of whatever the application presents. */
    public final void applyConnection(Connection connection) {
        boolean offered = connection != null && connection.offered();
        this.connectionAuthorizationUrl = offered ? connection.authorizationUrl() : null;
        this.connectionTokenUrl = offered ? connection.tokenUrl() : null;
        this.connectionScopes = offered ? blankToNull(connection.scopes()) : null;
        this.connectionClientAuth =
                offered ? java.util.Objects.requireNonNullElse(connection.clientAuth(), TokenClientAuth.BASIC) : null;
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

    public boolean isAllowPrivateDestination() {
        return allowPrivateDestination;
    }

    public boolean isCacheEnabled() {
        return cacheEnabled;
    }

    public boolean isNormalizeJson() {
        return normalizeJson;
    }

    public String getJsonArrayPaths() {
        return jsonArrayPaths;
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

    public String getClientIdHeader() {
        return clientIdHeader;
    }

    /** The account connection this destination offers, or {@link Connection#none()} when it offers none. */
    public Connection connection() {
        return new Connection(connectionAuthorizationUrl, connectionTokenUrl, connectionScopes, connectionClientAuth);
    }

    public boolean offersConnection() {
        return connectionAuthorizationUrl != null && connectionTokenUrl != null;
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
