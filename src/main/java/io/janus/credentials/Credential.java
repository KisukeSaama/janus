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

    @Column(name = "header_name", length = 100)
    private String headerName;

    /** The query parameter a key is presented as, for {@link AuthType#API_KEY_QUERY}. */
    @Column(name = "query_parameter", length = 100)
    private String queryParameter;

    /** Where client credentials are exchanged, for {@link AuthType#OAUTH2_CLIENT_CREDENTIALS}. */
    @Column(name = "token_url", length = 500)
    private String tokenUrl;

    @Column(name = "token_scopes", length = 500)
    private String tokenScopes;

    @Enumerated(EnumType.STRING)
    @Column(name = "token_client_auth", length = 16)
    private TokenClientAuth tokenClientAuth;

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
                provider.getTokenClientAuth());
    }

    /** Keeps personal credential metadata aligned with the administrator-owned API contract. */
    public void adoptProviderStrategy(AuthType previousType) {
        describe(name, strategyOf(provider), expiresAt, enabled);
        if (previousType != provider.getAuthType()) {
            enabled = false;
            requiresReprovision = !provider.getAuthType().anonymous();
        }
    }

    /**
     * How a secret is presented, and the settings that belong to that one way of presenting it.
     *
     * <p>Grouped into a record rather than passed as five loose parameters, for the same reason the
     * traffic policy is: they are only meaningful together, and only one combination at a time is
     * valid. {@code describe} clears the ones the chosen type does not use, which is what keeps the
     * database's check constraints satisfiable across a change of type.
     */
    public record Strategy(
            AuthType authType,
            String headerName,
            String queryParameter,
            String tokenUrl,
            String tokenScopes,
            TokenClientAuth tokenClientAuth) {

        /** For the three types that need nothing beyond the stored value. */
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
        this.headerName = type == AuthType.API_KEY_HEADER ? strategy.headerName() : null;
        this.queryParameter = type == AuthType.API_KEY_QUERY ? strategy.queryParameter() : null;
        this.tokenUrl = type.exchanged() ? strategy.tokenUrl() : null;
        this.tokenScopes = type.exchanged() ? blankToNull(strategy.tokenScopes()) : null;
        this.tokenClientAuth =
                type.exchanged() ? Objects.requireNonNullElse(strategy.tokenClientAuth(), TokenClientAuth.BASIC) : null;
        this.enabled = enabled;
        setExpiresAt(type.anonymous() ? null : expiresAt);
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

    public String getSecretPath() {
        return secretPath;
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
