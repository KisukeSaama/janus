package io.janus.gateway;

import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * The stored responses, bounded by count and by bytes and evicted least-recently-used first.
 *
 * <p>Deliberately in memory and per instance, like the verified-key cache: a stored response is an
 * optimisation, never a source of truth, so losing the store on restart costs latency and nothing
 * else. Running more than one replica simply means each one warms separately.
 *
 * <p>Nothing is stored before the request that produced it was authorised, and every key names the
 * credential it was fetched with, so an application that loses its grant cannot be served from an
 * entry it once caused: authorisation happens first, and it no longer passes.
 */
@Component
public class ResponseCache {

    /**
     * One stored response, already stripped of upstream authentication material and already
     * scrubbed of the credential, exactly as it was returned the first time.
     *
     * @param freshUntilMillis wall clock instant until which this may be served without asking
     * @param staleUntilMillis instant until which it may still answer while the upstream is failing
     * @param vary             request headers that were part of this representation's identity
     */
    public record Entry(
            int status,
            HttpHeaders headers,
            byte[] body,
            String etag,
            String lastModified,
            long storedAtMillis,
            long freshUntilMillis,
            long staleUntilMillis,
            Map<String, String> vary) {

        public boolean fresh(long nowMillis) {
            return nowMillis < freshUntilMillis;
        }

        public boolean servableStale(long nowMillis) {
            return nowMillis < staleUntilMillis;
        }

        public boolean revalidatable() {
            return etag != null || lastModified != null;
        }

        public long ageSeconds(long nowMillis) {
            return Math.max(0, (nowMillis - storedAtMillis) / 1000);
        }
        /** Bodies dominate, but an entry with no body still costs a key, headers, and a map. */
        long weight() {
            return body.length + 1024L;
        }
    }

    public record Stats(
            boolean enabled,
            int entries,
            long bytes,
            int maxEntries,
            long maxBytes,
            long stores,
            long evictions,
            Map<String, Long> outcomes) {}

    private final boolean enabled;
    private final int maxEntries;
    private final int maxEntryBytes;
    private final long maxTotalBytes;

    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(64, 0.75f, true);
    private final Map<CacheStatus, LongAdder> outcomes = new EnumMap<>(CacheStatus.class);
    private final LongAdder stores = new LongAdder();
    private final LongAdder evictions = new LongAdder();
    private long bytes;

    public ResponseCache(GatewayTrafficProperties properties) {
        var cache = properties.cache();
        this.enabled = cache.enabled();
        this.maxEntries = Math.max(0, cache.maxEntries());
        this.maxEntryBytes = Math.max(0, cache.maxEntryBytes());
        this.maxTotalBytes = Math.max(0, cache.maxTotalBytes());
        for (CacheStatus status : CacheStatus.values()) outcomes.put(status, new LongAdder());
    }

    public boolean isEnabled() {
        return enabled && maxEntries > 0;
    }

    /**
     * Returns the entry addressed by this key, fresh or not, provided the request agrees with the
     * headers the upstream said the representation varies by. The caller decides what to do with a
     * stale one; this store only reports what it holds.
     */
    public Optional<Entry> lookup(String key, HttpHeaders request) {
        Entry entry;
        synchronized (entries) {
            entry = entries.get(key);
        }
        if (entry == null) return Optional.empty();
        for (var expected : entry.vary().entrySet())
            if (!expected.getValue().equals(varyValue(request, expected.getKey()))) return Optional.empty();
        return Optional.of(entry);
    }

    public void store(String key, Entry entry) {
        if (!isEnabled() || entry.body().length > maxEntryBytes) return;
        synchronized (entries) {
            var replaced = entries.put(key, entry);
            bytes += entry.weight() - (replaced == null ? 0 : replaced.weight());
            var eldest = entries.entrySet().iterator();
            while ((entries.size() > maxEntries || bytes > maxTotalBytes) && eldest.hasNext()) {
                var victim = eldest.next();
                eldest.remove();
                bytes -= victim.getValue().weight();
                evictions.increment();
            }
        }
        stores.increment();
    }

    /** Replaces an entry's freshness and headers after the upstream confirmed it with a 304. */
    public void refresh(String key, Entry refreshed) {
        synchronized (entries) {
            if (entries.containsKey(key)) {
                var replaced = entries.put(key, refreshed);
                bytes += refreshed.weight() - (replaced == null ? 0 : replaced.weight());
            }
        }
    }

    /** Drops what a write to {@code decodedPath} made questionable: that resource and its members. */
    public int invalidateResource(UUID providerId, UUID credentialId, String decodedPath) {
        String prefix = CachePolicy.resourcePrefix(providerId, credentialId);
        return removeIf(key -> CachePolicy.covers(key, prefix, decodedPath));
    }

    public int invalidateProvider(UUID providerId) {
        String prefix = CachePolicy.providerPrefix(providerId);
        return removeIf(key -> key.startsWith(prefix));
    }

    public int invalidateCredential(UUID credentialId) {
        String token = CachePolicy.credentialToken(credentialId);
        return removeIf(key -> key.contains(token));
    }

    public int clear() {
        return removeIf(key -> true);
    }

    public void record(CacheStatus status) {
        outcomes.get(status).increment();
    }

    public Stats stats() {
        var counts = new LinkedHashMap<String, Long>();
        outcomes.forEach((status, count) -> counts.put(status.name(), count.sum()));
        synchronized (entries) {
            return new Stats(
                    isEnabled(),
                    entries.size(),
                    bytes,
                    maxEntries,
                    maxTotalBytes,
                    stores.sum(),
                    evictions.sum(),
                    counts);
        }
    }

    private int removeIf(Predicate<String> matches) {
        synchronized (entries) {
            int before = entries.size();
            entries.entrySet().removeIf(entry -> {
                if (!matches.test(entry.getKey())) return false;
                bytes -= entry.getValue().weight();
                return true;
            });
            return before - entries.size();
        }
    }

    /** The value a stored representation was keyed on for one {@code Vary} header. */
    static String varyValue(HttpHeaders headers, String name) {
        return String.join(",", headers.getOrEmpty(name));
    }
}
