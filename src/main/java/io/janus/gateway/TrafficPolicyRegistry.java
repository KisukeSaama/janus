package io.janus.gateway;

import java.time.Instant;
import java.util.*;

import org.springframework.stereotype.Service;

import io.janus.credentials.UpstreamTokenCache;

/**
 * The seam between administration and the running gateway.
 *
 * <p>Cache entries and token buckets outlive the records that produced them, so every change to a
 * provider, credential, or grant has to be told to them. Administration calls this facade rather
 * than reaching into the store, for the same reason key rotation calls the key cache: a policy that
 * takes effect in five minutes is not a policy.
 */
@Service
public class TrafficPolicyRegistry {
    private final ResponseCache cache;
    private final RateLimiter limiter;
    private final UpstreamCooldown cooldown;
    private final UpstreamTokenCache tokens;

    public TrafficPolicyRegistry(
            ResponseCache cache, RateLimiter limiter, UpstreamCooldown cooldown, UpstreamTokenCache tokens) {
        this.cache = cache;
        this.limiter = limiter;
        this.cooldown = cooldown;
        this.tokens = tokens;
    }

    /** @return how many stored responses were dropped */
    public int forgetProvider(UUID providerId) {
        limiter.forget("provider:" + providerId);
        cooldown.clearProvider(providerId);
        return cache.invalidateProvider(providerId);
    }

    /**
     * Forgets everything held on behalf of one secret.
     *
     * <p>Both stores, and the second one matters: a rotated client secret leaves behind a token that
     * the provider may well keep honouring for an hour. Dropping the responses without dropping the
     * token would take back the credential everywhere except where it is actually being used.
     *
     * @return how many stored responses were dropped
     */
    public int forgetCredential(UUID credentialId) {
        tokens.invalidate(credentialId);
        return cache.invalidateCredential(credentialId);
    }

    public void forgetGrant(UUID grantId) {
        limiter.forget("grant:" + grantId);
    }

    /** @return how many stored responses were dropped */
    public int purgeCache() {
        return cache.clear();
    }

    /** @param cooldowns providers currently refusing traffic, and until when */
    public record Snapshot(ResponseCache.Stats cache, List<Cooldown> cooldowns) {}

    public record Cooldown(UUID providerId, Instant until, int status) {}

    public Snapshot snapshot() {
        var pauses = cooldown.active().stream()
                .map(pause -> new Cooldown(pause.providerId(), pause.until(), pause.status()))
                .sorted(Comparator.comparing(Cooldown::until))
                .toList();
        return new Snapshot(cache.stats(), pauses);
    }
}
