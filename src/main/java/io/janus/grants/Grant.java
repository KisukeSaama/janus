package io.janus.grants;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import io.janus.applications.Application;
import io.janus.credentials.Credential;
import io.janus.providers.Provider;

/**
 * Binds one application to one provider credential.
 *
 * <p>A grant admits a caller to a destination rather than to a subset of its surface: unless it says
 * otherwise, a service holding one reaches any path the provider exposes, exactly as it would were
 * it holding the key itself. What Janus decides is who calls, with which secret, and how often.
 *
 * <p>It may say otherwise, and that is all {@link GrantScope} is: a ceiling for the credential the
 * upstream cannot narrow itself. It is empty by default and empty means everything.
 */
@Entity
@Table(
        name = "grants",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_grant_app_provider",
                        columnNames = {"application_id", "provider_id"}))
public class Grant {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id")
    private Application application;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "provider_id")
    private Provider provider;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "credential_id")
    private Credential credential;

    @Column(nullable = false)
    private boolean enabled = true;

    /** This application's own allowance against this provider. Zero is no ceiling. */
    @Column(name = "rate_limit_per_minute", nullable = false)
    private int rateLimitPerMinute;

    /** How much of that allowance may be spent at once. Zero derives a tenth of the allowance. */
    @Column(name = "rate_limit_burst", nullable = false)
    private int rateLimitBurst;

    /** The path under which this grant admits calls. Null is the whole destination. */
    @Column(name = "path_prefix", length = 512)
    private String pathPrefix;

    /** The methods it admits, comma separated. Null is all of them. */
    @Column(name = "allowed_methods", length = 128)
    private String allowedMethods;

    /** Whether it may speak for the connected account, or only as the application itself. */
    @Column(name = "allow_account_identity", nullable = false)
    private boolean allowAccountIdentity = true;

    /**
     * The two columns above, read once rather than on every proxied call. Held here because a grant
     * is what the gateway's authorisation cache keeps, so this is parsed once per cached grant rather
     * than once per request. Two threads racing to fill it build the same immutable value and one of
     * them wins, which is why it needs nothing stronger than a plain field.
     */
    @Transient
    private GrantScope scope;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** For Hibernate only. */
    protected Grant() {}

    public Grant(Application application, Provider provider, Credential credential) {
        this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        bind(application, provider, credential);
    }

    /**
     * An application's own quota against one provider.
     *
     * @param perMinute what this application may ask of this provider per minute; 0 is no ceiling
     * @param burst how much of that allowance may be spent at once; 0 derives a tenth of it
     */
    public record Quota(int perMinute, int burst) {
        public Quota {
            if (burst > 0 && perMinute == 0)
                throw new IllegalArgumentException("A burst is only meaningful with a per-minute rate limit");
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * Ties the three records together, refusing the two combinations that would make a grant mean
     * something nobody stated.
     *
     * <p>The owner check is the load-bearing one for the separation between people: a service and
     * the API it may call belong to the same person, so no rule can be written across two registries
     * even if a query somewhere were widened. The credential follows its provider, so checking those
     * two settles all three.
     */
    public final void bind(Application application, Provider provider, Credential credential) {
        if (!credential.getProvider().getId().equals(provider.getId()))
            throw new IllegalArgumentException("Credential belongs to a different provider");
        if (!application.getOwner().getId().equals(credential.getOwnerId()))
            throw new IllegalArgumentException("Service and API activation belong to different owners");
        this.application = application;
        this.provider = provider;
        this.credential = credential;
    }

    public void applyQuota(Quota quota) {
        this.rateLimitPerMinute = quota.perMinute();
        this.rateLimitBurst = quota.burst();
    }

    /** Narrows what of the destination this grant admits, or widens it back to all of it. */
    public void applyScope(GrantScope scope) {
        scope.validate();
        this.pathPrefix = scope.storedPrefix();
        this.allowedMethods = scope.storedMethods();
        this.allowAccountIdentity = scope.admitsAccountIdentity();
        this.scope = scope;
    }

    public GrantScope getScope() {
        var held = scope;
        if (held == null) this.scope = held = GrantScope.of(pathPrefix, allowedMethods, allowAccountIdentity);
        return held;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public UUID getId() {
        return id;
    }

    public Application getApplication() {
        return application;
    }

    public Provider getProvider() {
        return provider;
    }

    public Credential getCredential() {
        return credential;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getRateLimitPerMinute() {
        return rateLimitPerMinute;
    }

    public int getRateLimitBurst() {
        return rateLimitBurst;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
