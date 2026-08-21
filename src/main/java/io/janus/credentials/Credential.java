package io.janus.credentials;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.*;

import io.janus.providers.Provider;

/** Metadata for a secret held in OpenBao. The secret value itself never reaches this table. */
@Entity
@Table(
        name = "credentials",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_credential_owner_provider",
                        columnNames = {"owner_id", "provider_id"}))
public class Credential {
    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(nullable = false, length = 120)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "provider_id")
    private Provider provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false, length = 32)
    private AuthType authType;

    /**
     * The header a key travels in, for {@link AuthType#API_KEY_HEADER} — and, for a signed request,
     * the header identifying who signed it, which is what lets the upstream pick a secret to verify
     * against.
     */
    @Column(name = "header_name", length = 100)
    private String headerName;

    /** The query parameter a key is presented as, for {@link AuthType#API_KEY_QUERY}. */
    @Column(name = "query_parameter", length = 100)
    private String queryParameter;

    /** Where the application's own credentials are exchanged, for the strategy that exchanges them. */
    @Column(name = "token_url", length = 500)
    private String tokenUrl;

    @Column(name = "token_scopes", length = 500)
    private String tokenScopes;

    @Enumerated(EnumType.STRING)
    @Column(name = "token_client_auth", length = 16)
    private TokenClientAuth tokenClientAuth;

    /** The header this API wants the client id on; a copy of the provider's, like everything here. */
    @Column(name = "client_id_header", length = 100)
    private String clientIdHeader;

    /**
     * The account connection's contract, copied from the provider like the strategy above it, so an
     * outbound request is built without reading two rows. Null throughout when the API offers none.
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

    /**
     * Whether an OAuth client of the connection's own has been supplied. Only consulted when the
     * connection does not share the application's stored secret; see {@link #connectionSecretPath()}.
     */
    @Column(name = "connection_provisioned", nullable = false)
    private boolean connectionProvisioned;

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

    /**
     * When somebody last agreed at the provider's own site, for an authorisation-code credential.
     *
     * <p>Null means nobody has, which is a different state from a credential that is merely disabled:
     * there is a client secret stored and no refresh token to go with it, so the console can offer the
     * one action that fixes it rather than reporting a failure.
     */
    @Column(name = "authorized_at")
    private Instant authorizedAt;

    /** Whom the stored refresh token speaks for, as the provider named them. Displayed, never sent. */
    @Column(name = "authorized_subject", length = 255)
    private String authorizedSubject;

    @Column(name = "secret_path", nullable = false, unique = true, length = 500)
    private String secretPath;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "requires_reprovision", nullable = false)
    private boolean requiresReprovision;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "expiry_stage_notified", length = 16)
    private ExpiryStage expiryStageNotified;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** For Hibernate only. */
    protected Credential() {}

    public Credential(
            UUID ownerId, Provider provider, String name, Strategy strategy, Instant expiresAt, boolean enabled) {
        this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.ownerId = ownerId;
        this.provider = provider;
        // Server-derived, and derived once: the path is where the stored secret lives for the rest of
        // this credential's life, so it must never move when the record is edited.
        this.secretPath = "janus/" + provider.getSlug() + "/" + this.id;
        describe(name, strategy, expiresAt, enabled);
    }

    /** Compatibility constructor for older fixtures. */
    // Reaches the deprecated owner deliberately: this constructor exists for the same fixtures that
    // accessor was kept for, and goes when they do.
    @SuppressWarnings("deprecation")
    public Credential(Provider provider, String name, Strategy strategy, Instant expiresAt, boolean enabled) {
        this(provider.getOwner().getId(), provider, name, strategy, expiresAt, enabled);
    }

    public static Strategy strategyOf(Provider provider) {
        return new Strategy(
                provider.getAuthType(),
                provider.getHeaderName(),
                provider.getQueryParameter(),
                provider.getTokenUrl(),
                provider.getTokenScopes(),
                provider.getTokenClientAuth(),
                provider.signatureSettings(),
                provider.getClientIdHeader());
    }

    /** Keeps personal credential metadata aligned with the administrator-owned API contract. */
    public void adoptProviderStrategy(AuthType previousType) {
        adoptProviderStrategy(previousType, connection());
    }

    /**
     * @param previousConnection what this credential was connected under before the API was edited
     */
    public void adoptProviderStrategy(AuthType previousType, Provider.Connection previousConnection) {
        describe(name, strategyOf(provider), expiresAt, enabled);
        applyConnection(provider.connection());
        if (previousType != provider.getAuthType()) {
            enabled = false;
            requiresReprovision = !provider.getAuthType().anonymous();
        }
        // Consent was given for the old contract. An authorisation page or a token endpoint that moved
        // means whatever was agreed to was agreed to somewhere else, so the credential goes back to
        // unauthorised rather than carrying a stale approval. Scopes changing does not: a narrower
        // grant is a real state, and the endpoint that needs more will say so.
        var current = provider.connection();
        if (!Objects.equals(previousConnection.authorizationUrl(), current.authorizationUrl())
                || !Objects.equals(previousConnection.tokenUrl(), current.tokenUrl())) forgetAuthorization();
    }

    /**
     * How a secret is presented, and the settings that belong to that one way of presenting it.
     *
     * <p>Grouped into a record rather than passed as eight loose parameters, for the same reason the
     * traffic policy is: they are only meaningful together, and only one combination at a time is
     * valid. {@code describe} clears the ones the chosen type does not use, which is what keeps the
     * database's check constraints satisfiable across a change of type.
     *
     * <p>The one strategy with a shape of its own carries it as a record rather than as six more loose
     * components, because it is validated as a whole and means nothing in pieces.
     */
    public record Strategy(
            AuthType authType,
            String headerName,
            String queryParameter,
            String tokenUrl,
            String tokenScopes,
            TokenClientAuth tokenClientAuth,
            SignatureSettings signature,
            String clientIdHeader) {

        /** For the strategies that were the whole vocabulary before signing was added. */
        public Strategy(
                AuthType authType,
                String headerName,
                String queryParameter,
                String tokenUrl,
                String tokenScopes,
                TokenClientAuth tokenClientAuth) {
            this(authType, headerName, queryParameter, tokenUrl, tokenScopes, tokenClientAuth, null);
        }

        /** For callers written before an exchange could also name a header for its client id. */
        public Strategy(
                AuthType authType,
                String headerName,
                String queryParameter,
                String tokenUrl,
                String tokenScopes,
                TokenClientAuth tokenClientAuth,
                SignatureSettings signature) {
            this(authType, headerName, queryParameter, tokenUrl, tokenScopes, tokenClientAuth, signature, null);
        }

        /** For the types that need nothing beyond the stored value. */
        public static Strategy of(AuthType authType) {
            return new Strategy(authType, null, null, null, null, null);
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * Applies everything an administrator may change about a stored secret's metadata.
     *
     * <p>Every setting that belongs to another strategy is cleared rather than left behind. A record
     * that kept a token URL after being changed to a bearer key would violate the check constraint
     * that ties the two together — and, worse, would read as if an exchange were still configured.
     *
     * <p>A deadline is one of those settings. Nothing is stored for an anonymous destination, so
     * nothing about it can stop working on a date, and the register must not announce that it will.
     */
    public final void describe(String name, Strategy strategy, Instant expiresAt, boolean enabled) {
        var type = strategy.authType();
        this.name = name;
        this.authType = type;
        this.headerName =
                type == AuthType.API_KEY_HEADER || type == AuthType.HMAC_SIGNATURE ? strategy.headerName() : null;
        this.queryParameter = type == AuthType.API_KEY_QUERY ? strategy.queryParameter() : null;
        this.tokenUrl = type.exchanged() ? strategy.tokenUrl() : null;
        this.tokenScopes = type.exchanged() ? blankToNull(strategy.tokenScopes()) : null;
        this.tokenClientAuth =
                type.exchanged() ? Objects.requireNonNullElse(strategy.tokenClientAuth(), TokenClientAuth.BASIC) : null;
        this.clientIdHeader = type.exchanged() ? blankToNull(strategy.clientIdHeader()) : null;
        applySignature(type, strategy.signature());

        this.enabled = enabled;
        // A destination that stores nothing for the application may still hold a connection, whose
        // client secret does expire. The deadline belongs to whatever is actually stored.
        setExpiresAt(type.anonymous() && !offersConnection() ? null : expiresAt);
    }

    /**
     * Copies the API's account connection onto this credential.
     *
     * <p>Losing a connection clears the consent with it. A refresh token is worthless once the
     * exchange that would spend it is gone, and leaving the record claiming an authorisation nothing
     * could act on is the state the check constraint exists to forbid.
     */
    public void applyConnection(Provider.Connection connection) {
        boolean offered = connection != null && connection.offered();
        this.connectionAuthorizationUrl = offered ? connection.authorizationUrl() : null;
        this.connectionTokenUrl = offered ? connection.tokenUrl() : null;
        this.connectionScopes = offered ? blankToNull(connection.scopes()) : null;
        this.connectionClientAuth =
                offered ? Objects.requireNonNullElse(connection.clientAuth(), TokenClientAuth.BASIC) : null;
        if (!offered) {
            this.connectionProvisioned = false;
            forgetAuthorization();
        }
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

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public void transferTo(UUID ownerId) {
        this.ownerId = ownerId;
    }

    public String getName() {
        return name;
    }

    public Provider getProvider() {
        return provider;
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

    public String getClientIdHeader() {
        return clientIdHeader;
    }

    public TokenClientAuth getTokenClientAuth() {
        return tokenClientAuth;
    }

    /** This credential's copy of the API's account connection; never null, possibly offering nothing. */
    public Provider.Connection connection() {
        return new Provider.Connection(
                connectionAuthorizationUrl, connectionTokenUrl, connectionScopes, connectionClientAuth);
    }

    public final boolean offersConnection() {
        return connectionAuthorizationUrl != null && connectionTokenUrl != null;
    }

    public String getConnectionAuthorizationUrl() {
        return connectionAuthorizationUrl;
    }

    public String getConnectionTokenUrl() {
        return connectionTokenUrl;
    }

    public String getConnectionScopes() {
        return connectionScopes;
    }

    public TokenClientAuth getConnectionClientAuth() {
        return connectionClientAuth;
    }

    public SignatureAlgorithm getSignatureAlgorithm() {
        return signatureAlgorithm;
    }

    /** The recipe for signing a request, or null when this credential does not sign one. */
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

    public String getSecretPath() {
        return secretPath;
    }

    /**
     * Where the refresh token for this credential lives, which is beside the client secret rather than
     * inside it: the two arrive at different times, from different people, and are replaced
     * independently — a provider may rotate the refresh token on every use while the client secret
     * stays as it was for years.
     */
    public String refreshTokenPath() {
        return secretPath + "/refresh";
    }

    /**
     * Where the OAuth client the connection exchanges with lives.
     *
     * <p>Usually it is the one already stored. When the application presents a client id and secret of
     * its own, that is the same OAuth client the person will agree to — Spotify and Twitch issue one
     * pair and mint both kinds of token from it, so asking for it twice would be asking somebody to
     * copy a value from one field into another. When the application presents anything else, the two
     * have nothing in common — a Discord bot token is not an OAuth client — and the connection needs
     * its own, which is what {@code connection_provisioned} records. An open destination stores nothing
     * for the application, so the ordinary path is free for the connection to use.
     */
    public String connectionSecretPath() {
        return connectionSharesApplicationSecret() ? secretPath : secretPath + "/connection";
    }

    /** Whether the connection exchanges with the credential the application already stores. */
    public boolean connectionSharesApplicationSecret() {
        return authType.anonymous() || authType == AuthType.OAUTH2_CLIENT_CREDENTIALS;
    }

    /**
     * Whether there is an OAuth client to send somebody to the provider with. Distinct from consent:
     * this is about the application's half being on file, before anyone has been asked to agree.
     */
    public boolean connectionUsable() {
        return offersConnection() && (connectionSharesApplicationSecret() || connectionProvisioned);
    }

    public boolean isConnectionProvisioned() {
        return connectionProvisioned;
    }

    public void markConnectionProvisioned() {
        this.connectionProvisioned = true;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean requiresReprovision() {
        return requiresReprovision;
    }

    public void markProvisioned() {
        requiresReprovision = false;
    }

    public Instant getAuthorizedAt() {
        return authorizedAt;
    }

    public String getAuthorizedSubject() {
        return authorizedSubject;
    }

    /**
     * Whether the account identity is offered here but nobody has agreed to it yet. Not a failure:
     * it is the one state the console repairs with a button rather than a form.
     */
    public boolean awaitingAuthorization() {
        return offersConnection() && authorizedAt == null;
    }

    /** Whether this credential can present the account identity right now. */
    public boolean connected() {
        return connectionUsable() && authorizedAt != null;
    }

    /** Records that consent was given, and whom the provider says it was given by. */
    public void authorized(String subject) {
        this.authorizedAt = Instant.now();
        this.authorizedSubject = blankToNull(subject);
    }

    /** Drops the consent, which is what a revoked or replaced authorisation leaves behind. */
    public void forgetAuthorization() {
        this.authorizedAt = null;
        this.authorizedSubject = null;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    /**
     * A different deadline is a different promise, so the stages already announced stop applying and
     * the next sweep starts from silence. Rearming here rather than at the call site is what keeps a
     * prolonged key from staying quiet all the way to its new expiry.
     */
    private void setExpiresAt(Instant expiresAt) {
        if (!Objects.equals(this.expiresAt, expiresAt)) this.expiryStageNotified = null;
        this.expiresAt = expiresAt;
    }

    public ExpiryStage getExpiryStageNotified() {
        return expiryStageNotified;
    }

    /**
     * Package-private on purpose: production claims a stage through the repository's conditional
     * update, which is what makes an announcement happen once across instances. This exists so the
     * rearming rule above can be exercised without a database.
     */
    void claimExpiryStage(ExpiryStage stage) {
        this.expiryStageNotified = stage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
