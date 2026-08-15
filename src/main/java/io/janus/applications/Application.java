package io.janus.applications;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

import jakarta.persistence.*;

import io.janus.accounts.Account;

/**
 * Machine identity allowed to approach the gateway.
 *
 * <p>State is encapsulated because lazy associations elsewhere are resolved through accessors;
 * direct field reads on a Hibernate proxy bypass initialisation and silently return null.
 *
 * <p>Identity and timestamps are set by the constructor rather than by {@code @PrePersist}. With an
 * assigned identifier Hibernate defers the insert to flush time, so a callback-populated
 * {@code createdAt} is still null when the response to a create is written.
 */
@Entity
@Table(
        name = "applications",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_application_owner_name",
                        columnNames = {"owner_id", "name"}))
public class Application {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private Account owner;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "api_key_hash", nullable = false, length = 100)
    private String apiKeyHash;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "api_key_rotated_at", nullable = false)
    private Instant apiKeyRotatedAt;

    /**
     * Which browser origins may present this service's tokens. Empty is the default and means any:
     * a bearer token is what authorises a call, and an {@code Origin} header is declared by the
     * browser rather than proven, so requiring one buys defence in depth and not much else. A
     * deployment that wants that depth fills this in, and then a token replayed from another page
     * stops working.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "application_origins", joinColumns = @JoinColumn(name = "application_id", nullable = false))
    @Column(name = "origin", nullable = false, length = 255)
    private Set<String> allowedOrigins = new LinkedHashSet<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** For Hibernate only. */
    protected Application() {}

    public Application(Account owner, String name, String description, boolean enabled, String apiKeyHash) {
        this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.apiKeyRotatedAt = this.createdAt;
        this.apiKeyHash = apiKeyHash;
        this.owner = owner;
        describe(name, description, enabled);
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Applies everything an administrator may change about an identity. */
    public final void describe(String name, String description, boolean enabled) {
        this.name = name;
        this.description = description;
        this.enabled = enabled;
    }

    /**
     * Replaces the whole list, stated as a set the way route rules are: reviewing what may call from
     * where means reading one list rather than replaying its edits.
     *
     * <p>Each entry must be a scheme and a host, optionally a port, and nothing else — an origin is
     * not a URL. A path or a wildcard here would be silently ignored by every browser, which is worse
     * than being refused, because it reads as a restriction that is not one.
     */
    public void allowOrigins(Collection<String> origins) {
        var replacement = new LinkedHashSet<String>();
        for (String origin : origins) {
            String value = origin == null ? "" : origin.trim();
            if (!ORIGIN.matcher(value).matches())
                throw new IllegalArgumentException(
                        "'" + value + "' is not an origin; state a scheme and a host, as in https://example.com");
            replacement.add(value);
        }
        if (replacement.size() > MAX_ORIGINS)
            throw new IllegalArgumentException("A service may declare at most " + MAX_ORIGINS + " origins");
        this.allowedOrigins = replacement;
    }

    /** Whether a browser at this origin may present this service's tokens. */
    public boolean allowsOrigin(String origin) {
        return allowedOrigins.isEmpty() || (origin != null && allowedOrigins.contains(origin));
    }

    public Set<String> getAllowedOrigins() {
        return Collections.unmodifiableSet(allowedOrigins);
    }

    private static final int MAX_ORIGINS = 10;
    private static final Pattern ORIGIN = Pattern.compile("https?://[a-zA-Z0-9.-]+(:\\d{1,5})?");

    /**
     * Replacing the hash is what ages a key, so the timestamp moves with it and never separately:
     * registration time answers "when was this registered", not "how old is the key in circulation".
     */
    public void rotateApiKey(String apiKeyHash) {
        this.apiKeyHash = apiKeyHash;
        this.apiKeyRotatedAt = Instant.now();
    }

    /**
     * Hands the identity to somebody else. The caller must drop the cached key with it: the verified
     * principal carries the owner, and the gateway resolves provider slugs within that namespace.
     */
    public void transferTo(Account owner) {
        this.owner = owner;
    }

    public Account getOwner() {
        return owner;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getApiKeyHash() {
        return apiKeyHash;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getApiKeyRotatedAt() {
        return apiKeyRotatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
