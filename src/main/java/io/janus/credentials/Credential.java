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

    /** Where credentials are exchanged, for the two strategies that exchange anything. */
    @Column(name = "token_url", length = 500)
    private String tokenUrl;

    @Column(name = "token_scopes", length = 500)
    private String tokenScopes;

    @Enumerated(EnumType.STRING)
    @Column(name = "token_client_auth", length = 16)
    private TokenClientAuth tokenClientAuth;

    /** Where a person is sent to agree, for {@link AuthType#OAUTH2_AUTHORIZATION_CODE}. */
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
                provider.getAuthorizationUrl(),
                provider.signatureSettings());
    }

    /** Keeps personal credential metadata aligned with the administrator-owned API contract. */
    public void adoptProviderStrategy(AuthType previousType) {
        describe(name, strategyOf(provider), expiresAt, enabled);
        if (previousType != provider.getAuthType()) {
            enabled = false;
            requiresReprovision = !provider.getAuthType().anonymous();
            // Consent was given for the old contract. Whatever it authorised, it did not authorise
            // this, so the credential goes back to unauthorised rather than carrying a stale approval.
            forgetAuthorization();
        }
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
            String authorizationUrl,
            SignatureSettings signature) {

        /** For the strategies that were the whole vocabulary before consent and signing were added. */
        public Strategy(
                AuthType authType,
                String headerName,
                String queryParameter,
                String tokenUrl,
                String tokenScopes,
                TokenClientAuth tokenClientAuth) {
            this(authType, headerName, queryParameter, tokenUrl, tokenScopes, tokenClientAuth, null, null);
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
    public void describe(String name, Strategy strategy, Instant expiresAt, boolean enabled) {
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
        this.authorizationUrl = type.consented() ? strategy.authorizationUrl() : null;
        applySignature(type, strategy.signature());

        this.enabled = enabled;
        setExpiresAt(type.anonymous() ? null : expiresAt);
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

    public TokenClientAuth getTokenClientAuth() {
        return tokenClientAuth;
    }

    public String getAuthorizationUrl() {
        return authorizationUrl;
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
     * Whether this credential still needs somebody to agree at the provider before it can be used.
     * Only ever true for the one strategy that asks a person rather than an administrator.
     */
    public boolean awaitingAuthorization() {
        return authType.consented() && authorizedAt == null;
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
