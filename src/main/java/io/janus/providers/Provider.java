package io.janus.providers;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import io.janus.accounts.Account;

/**
 * Fixed upstream destination. Gateway callers never supply a URL; they name a provider slug.
 *
 * <p>The traffic policy lives here rather than in a service because its one rule — a burst is
 * meaningless without an allowance to burst against — must hold for every path that writes it.
 *
 * <p>A slug is unique within its owner, not across the deployment: two people each registering the
 * Spotify API is the ordinary case. The gateway resolves a slug within the namespace of whoever's
 * application is calling, so {@code /gateway/spotify/...} still means one destination per caller.
 */
@Entity
@Table(
        name = "providers",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_provider_owner_slug",
                        columnNames = {"owner_id", "slug"}))
public class Provider {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private Account owner;

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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** For Hibernate only. */
    protected Provider() {}

    public Provider(Account owner, String name, String slug, String baseUrl, boolean enabled, TrafficPolicy traffic) {
        this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.owner = owner;
        describe(name, slug, baseUrl, enabled);
        applyTrafficPolicy(traffic);
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
     * Hands the destination to somebody else. The slug moves with it, so the caller must invalidate
     * what was addressed by it — {@code TrafficPolicyRegistry.forgetProvider} — as for any edit.
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
