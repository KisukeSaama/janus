package io.janus.gateway;

import java.time.Instant;
import java.util.*;

import org.springframework.stereotype.Service;

import io.janus.credentials.UpstreamTokenCache;
import io.janus.gateway.graphql.PersistedQueries;

/**
 * The seam between administration and the running gateway.
 *
 * <p>Cache entries and token buckets outlive the records that produced them, so every change to a
 * provider, credential, or grant has to be told to them. Administration calls this facade rather
 * than reaching into the store, for the same reason key rotation calls the key cache: a policy that
 * takes effect in five minutes is not a policy.
 *
 * <p>Open subscriptions outlive them too, and by much more than five minutes. Every change here closes
 * the streams it has made unauthorised; see {@link GraphQlStreams}.
 */
@Service
public class TrafficPolicyRegistry {
    private final ResponseCache cache;
    private final AuthorizationCache authorizations;
    private final RateLimiter limiter;
    private final UpstreamCooldown cooldown;
    private final UpstreamTokenCache tokens;
    private final IdentityMemory identities;
    private final GraphQlStreams streams;
    private final PersistedQueries persistedQueries;

    public TrafficPolicyRegistry(
            ResponseCache cache,
            AuthorizationCache authorizations,
            RateLimiter limiter,
            UpstreamCooldown cooldown,
            UpstreamTokenCache tokens,
            IdentityMemory identities,
            GraphQlStreams streams,
            PersistedQueries persistedQueries) {
        this.cache = cache;
        this.authorizations = authorizations;
        this.limiter = limiter;
        this.cooldown = cooldown;
        this.tokens = tokens;
        this.identities = identities;
        this.streams = streams;
        this.persistedQueries = persistedQueries;
    }

    /** @return how many stored responses were dropped */
    public int forgetProvider(UUID providerId) {
        limiter.forget("provider:" + providerId);
        cooldown.clearProvider(providerId);
        // Before the responses: a request admitted by a stale grant would otherwise be authorised
        // against the destination this change was made to remove.
        authorizations.forgetProvider(providerId);
        // A changed endpoint, or changed limits, is a different contract for every stream open on
        // the old one. The documents learned for its hashes go with it, since the endpoint they were
        // verified against may no longer be the one being called.
        streams.closeProvider(providerId);
        persistedQueries.forget(providerId);
        return cache.invalidateProvider(providerId);
    }

    /**
     * Forgets everything held on behalf of one secret.
     *
     * <p>Every store, and the token one matters most: a rotated client secret leaves behind a token
     * that the provider may well keep honouring for an hour. Dropping the responses without dropping
     * the token would take back the credential everywhere except where it is actually being used.
     *
     * <p>What was learned about which identity each endpoint answers to goes as well. It was learned
     * from what the upstream said to a particular pair of credentials, and this is the announcement
     * that the pair is no longer what it was.
     *
     * @return how many stored responses were dropped
     */
    public int forgetCredential(UUID credentialId) {
        tokens.invalidate(credentialId);
        identities.forget(credentialId);
        authorizations.forgetCredential(credentialId);
        streams.closeCredential(credentialId);
        return cache.invalidateCredential(credentialId);
    }

    public void forgetGrant(UUID grantId) {
        limiter.forget("grant:" + grantId);
        authorizations.forgetGrant(grantId);
        streams.closeGrant(grantId);
    }

    /**
     * A service disabled, deleted, handed to somebody else or given a new key. Its tokens and verified
     * key are dropped where they are held; what is left here is whatever it already has open.
     */
    public void forgetApplication(UUID applicationId) {
        streams.closeApplication(applicationId);
    }

    /** @return how many stored responses were dropped */
    public int purgeCache() {
        // The registry reads go too. An operator purging the cache is saying they no longer trust
        // what this instance holds, and a grant resolved four seconds ago is part of that.
        authorizations.clear();
        return cache.clear();
    }

    /**
     * @param authorizations the registry reads spared, which is what a hit costs the database
     * @param cooldowns      providers currently refusing traffic, and until when
     */
    public record Snapshot(
            ResponseCache.Stats cache, AuthorizationCache.Stats authorizations, List<Cooldown> cooldowns) {}

    public record Cooldown(UUID providerId, Instant until, int status) {}

    public Snapshot snapshot() {
        var pauses = cooldown.active().stream()
                .map(pause -> new Cooldown(pause.providerId(), pause.until(), pause.status()))
                .sorted(Comparator.comparing(Cooldown::until))
                .toList();
        return new Snapshot(cache.stats(), authorizations.stats(), pauses);
    }
}
