package io.janus.gateway;

import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import io.janus.credentials.Identity;

/**
 * The stored responses, bounded by count and by bytes and evicted least-recently-used first.
 *
 * <p>Deliberately in memory and per instance, like the verified-key cache: a stored response is an
 * optimisation, never a source of truth, so losing the store on restart costs latency and nothing
 * else. Running more than one replica means each one warms separately — and, less comfortably, that
 * an administrative purge or a rotated credential invalidates only the instance that received the
 * call. What keeps this store honest on one instance is that every such change drops what it made
 * questionable in the same request; that is the guarantee a second replica breaks, not the memory
 * footprint. See the deployment note in the README before scaling out.
 *
 * <p>Nothing is stored before the request that produced it was authorised, and every key names the
 * credential it was fetched with, so an application that loses its grant cannot be served from an
 * entry it once caused: authorisation happens first, and it no longer passes.
 *
 * <p>A resource that varies is held once per representation rather than once in total. The key the
 * caller computes addresses the resource; the names an upstream said it varies by are remembered
 * against that key, and the values the request carried for those names are folded into a secondary
 * key — RFC 9111 §4.1. Without it, two representations of the same resource evicted each other
 * indefinitely and the store answered nothing while looking perfectly healthy.
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

        /**
         * The freshness lifetime this entry was stored with, counted from when it was fetched.
         *
         * <p>The whole lifetime, not what remains of it: a caller subtracts {@code Age} from
         * {@code max-age} itself, so announcing the remainder alongside an {@code Age} header would
         * have it subtract the same seconds twice.
         */
        public long lifetimeSeconds() {
            return Math.max(0, (freshUntilMillis - storedAtMillis) / 1000);
        }
        /** Bodies dominate, but an entry with no body still costs a key, headers, and a map. */
        long weight() {
            return body.length + 1024L;
        }
    }

    /**
     * @param oversized responses served but never stored, being larger than one entry may be
     * @param variants  representations held beyond the first for a resource that varies
     */
    public record Stats(
            boolean enabled,
            int entries,
            long bytes,
            int maxEntries,
            long maxBytes,
            long stores,
            long evictions,
            long oversized,
            int variants,
            Map<String, Long> outcomes) {}

    private final boolean enabled;
    private final int maxEntries;
    private final int maxEntryBytes;
    private final long maxTotalBytes;

    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(64, 0.75f, true);
    /** Per resource key, the header names its upstream said the representation varies by. */
    private final LinkedHashMap<String, List<String>> variants = new LinkedHashMap<>(64, 0.75f, true);

    private final Map<CacheStatus, LongAdder> outcomes = new EnumMap<>(CacheStatus.class);
    private final LongAdder stores = new LongAdder();
    private final LongAdder evictions = new LongAdder();
    private final LongAdder oversized = new LongAdder();
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
            entry = entries.get(variant(key, request));
        }
        if (entry == null) return Optional.empty();
        // The secondary key already accounts for every name this resource was known to vary by. This
        // still holds, for the one exchange during which a new name appears: it is what keeps a
        // representation from being served to a request that asked for a different one.
        for (var expected : entry.vary().entrySet())
            if (!expected.getValue().equals(varyValue(request, expected.getKey()))) return Optional.empty();
        return Optional.of(entry);
    }

    public void store(String key, Entry entry) {
        if (!isEnabled()) return;
        if (entry.body().length > maxEntryBytes) {
            oversized.increment();
            return;
        }
        synchronized (entries) {
            if (entry.vary().isEmpty()) variants.remove(key);
            else variants.put(key, List.copyOf(entry.vary().keySet()));
            var replaced = entries.put(variant(key, entry.vary()), entry);
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
            String variant = variant(key, refreshed.vary());
            if (entries.containsKey(variant)) {
                var replaced = entries.put(variant, refreshed);
                bytes += refreshed.weight() - (replaced == null ? 0 : replaced.weight());
            }
        }
    }

    /**
     * Drops what a write to {@code decodedPath} made questionable: that resource and its members.
     *
     * <p>Both identities, whichever one performed the write. A playlist somebody adds a track to as
     * themselves is the same playlist the application read a moment ago, and an invalidation that
     * covered only the identity that wrote would leave the other serving the version from before.
     */
    public int invalidateResource(UUID providerId, UUID credentialId, String decodedPath) {
        int dropped = 0;
        for (Identity identity : Identity.values()) {
            String prefix = CachePolicy.resourcePrefix(providerId, credentialId, identity);
            dropped += removeIf(key -> CachePolicy.covers(key, prefix, decodedPath));
        }
        return dropped;
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
                    oversized.sum(),
                    variants.size(),
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
            // A resource key is a prefix of every secondary key derived from it, so the same
            // predicate names both. Left behind, these would send the next lookup to a key nothing
            // is stored under any more.
            variants.keySet().removeIf(matches);
            return before - entries.size();
        }
    }

    /**
     * Where this request's representation of {@code key} is stored.
     *
     * <p>The first response for a resource is stored under the resource key itself. Only once an
     * upstream has said the resource varies does a secondary key appear, and it appears for the
     * request that comes after — which is exactly RFC 9111's rule that a stored response is selected
     * using the header names a stored response gave.
     */
    private String variant(String key, HttpHeaders request) {
        var names = variants.get(key);
        if (names == null || names.isEmpty()) return key;
        var values = new LinkedHashMap<String, String>();
        for (String name : names) values.put(name, varyValue(request, name));
        return variant(key, values);
    }

    /** The same address, derived from what a stored entry recorded rather than from a request. */
    private static String variant(String key, Map<String, String> vary) {
        if (vary.isEmpty()) return key;
        var joined = new StringJoiner("|");
        vary.forEach((name, value) -> joined.add(name + "=" + value));
        return key + CachePolicy.SEPARATOR + CachePolicy.digest(joined.toString());
    }

    /** The value a stored representation was keyed on for one {@code Vary} header. */
    static String varyValue(HttpHeaders headers, String name) {
        return String.join(",", headers.getOrEmpty(name));
    }
}
