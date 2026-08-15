package io.janus.gateway;

import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import io.janus.grants.Grant;
import io.janus.providers.Provider;

/**
 * The two registry reads every proxied call used to make, held for a few seconds.
 *
 * <p>A served response cost three round trips to PostgreSQL before this existed — the application,
 * the destination, and the grant — and it cost them even on a cache hit, where nothing else left the
 * process at all. With a connection pool sized for a proxy that only reads a grant, that was the
 * ceiling on throughput. The application is still read on every request, because it is what
 * authentication is proving; the other two are what this holds.
 *
 * <p>Only resolutions are stored, never refusals. An unknown slug and a missing grant each cost
 * their query every time, which is deliberate twice over: a caller cannot fill this by inventing
 * names, and a grant that starts existing is honoured immediately rather than after a timeout.
 *
 * <p>The lifetime is short and the invalidation is explicit — {@link TrafficPolicyRegistry} drops
 * what an administrative change made questionable, exactly as it does for stored responses and
 * verified keys. The timeout only backstops changes made outside this instance.
 *
 * <p><strong>Per instance, like every other cache here.</strong> An entry held by one replica is not
 * dropped by an administrative change received by another; see the note in {@link ResponseCache}.
 *
 * <p>What is stored are detached entities, shared between requests and read from several threads at
 * once. That is sound only because of how they are used: the gateway path reads them and never
 * writes to them, every administrative path loads its own managed copy through a repository, and
 * {@code findActive} fetches the credential a proxied call needs, so no lazy association is resolved
 * outside the transaction that loaded it. The grant's own provider and application associations are
 * <em>not</em> fetched, which is why nothing here reads them: the identifiers they would supply are
 * in the key already.
 */
@Component
public class AuthorizationCache {

    private record Entry<T>(T value, long expiresAtNanos) {}

    public record Stats(boolean enabled, int providers, int grants, long hits, long misses) {}

    private final boolean enabled;
    private final long ttlNanos;

    private final Map<String, Entry<Provider>> providers;
    private final Map<String, Entry<Grant>> grants;
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    public AuthorizationCache(GatewayTrafficProperties properties) {
        var authorization = properties.authorization();
        this.enabled = authorization.enabled() && authorization.ttlSeconds() > 0 && authorization.maxEntries() > 0;
        this.ttlNanos = Math.max(0, authorization.ttlSeconds()) * 1_000_000_000L;
        this.providers = bounded(Math.max(1, authorization.maxEntries()));
        this.grants = bounded(Math.max(1, authorization.maxEntries()));
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** The enabled destination this slug names, from memory when it was resolved a moment ago. */
    public Optional<Provider> provider(String slug, Supplier<Optional<Provider>> loader) {
        if (!enabled) return loader.get();
        var held = live(providers, slug);
        if (held != null) {
            hits.increment();
            return Optional.of(held);
        }
        misses.increment();
        var resolved = loader.get();
        resolved.ifPresent(provider -> providers.put(slug, new Entry<>(provider, System.nanoTime() + ttlNanos)));
        return resolved;
    }

    /** The active grant admitting this application to this destination, with its credential. */
    public Optional<Grant> grant(UUID applicationId, UUID providerId, Supplier<Optional<Grant>> loader) {
        if (!enabled) return loader.get();
        String key = applicationId + ":" + providerId;
        var held = live(grants, key);
        if (held != null) {
            hits.increment();
            return Optional.of(held);
        }
        misses.increment();
        var resolved = loader.get();
        resolved.ifPresent(grant -> grants.put(key, new Entry<>(grant, System.nanoTime() + ttlNanos)));
        return resolved;
    }

    /** Drops the destination itself and every grant leading to it. */
    public void forgetProvider(UUID providerId) {
        removeIf(providers, provider -> provider.getId().equals(providerId));
        String suffix = ":" + providerId;
        synchronized (grants) {
            grants.keySet().removeIf(key -> key.endsWith(suffix));
        }
    }

    public void forgetGrant(UUID grantId) {
        removeIf(grants, grant -> grant.getId().equals(grantId));
    }

    /**
     * Drops every grant carrying this credential. A disabled, re-provisioned or rotated secret must
     * stop being usable at once, and the grant is what carries it onto the request.
     */
    public void forgetCredential(UUID credentialId) {
        removeIf(grants, grant -> grant.getCredential().getId().equals(credentialId));
    }

    public void clear() {
        providers.clear();
        grants.clear();
    }

    public Stats stats() {
        return new Stats(enabled, providers.size(), grants.size(), hits.sum(), misses.sum());
    }

    private <T> T live(Map<String, Entry<T>> entries, String key) {
        var entry = entries.get(key);
        if (entry == null) return null;
        if (System.nanoTime() - entry.expiresAtNanos() >= 0) {
            entries.remove(key, entry);
            return null;
        }
        return entry.value();
    }

    private static <T> void removeIf(Map<String, Entry<T>> entries, Predicate<T> matches) {
        synchronized (entries) {
            entries.values().removeIf(entry -> matches.test(entry.value()));
        }
    }

    private static <T> Map<String, Entry<T>> bounded(int maxEntries) {
        return Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
            // Qualified deliberately. Inside a LinkedHashMap subclass the simple name Entry resolves to
            // the inherited Map.Entry rather than to the record above, and the override stops overriding.
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, AuthorizationCache.Entry<T>> eldest) {
                return size() > maxEntries;
            }
        });
    }
}
