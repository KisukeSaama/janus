package io.janus.gateway;

import java.util.*;

import org.springframework.stereotype.Component;

/**
 * Token buckets for the two ceilings the gateway enforces: what one application may ask of a
 * provider, and what the deployment as a whole may send to that provider.
 *
 * <p>A bucket refills continuously rather than resetting on a fixed boundary, so a caller cannot
 * spend a whole minute's allowance in the last second of one window and again in the first second
 * of the next. Buckets are per instance and in memory, like the authentication throttle: running
 * more than one replica divides every allowance by the number of replicas until the counters move
 * to a shared store, which the README records as a production prerequisite.
 */
@Component
public class RateLimiter {
    private static final long NANOS_PER_MINUTE = 60_000_000_000L;
    private static final int MAX_TRACKED_BUCKETS = 50_000;

    /**
     * @param allowed          whether a permit was issued
     * @param limit            the per-minute allowance in force
     * @param remaining        whole permits left immediately after this decision
     * @param waitMillis       how long until the next permit, zero when one is available now
     * @param fullRefillMillis how long until the bucket is back at capacity
     */
    public record Decision(boolean allowed, int limit, long remaining, long waitMillis, long fullRefillMillis) {
        /** The answer given when no ceiling is configured: unlimited, and no headers worth writing. */
        static Decision unlimited() {
            return new Decision(true, 0, 0, 0, 0);
        }

        public boolean measured() {
            return limit > 0;
        }

        public long retryAfterSeconds() {
            return Math.max(1, (waitMillis + 999) / 1000);
        }

        public long resetSeconds() {
            return (fullRefillMillis + 999) / 1000;
        }
    }

    private static final class Bucket {
        long tokens;
        long lastRefillNanos;

        Bucket(long tokens, long now) {
            this.tokens = tokens;
            this.lastRefillNanos = now;
        }
    }

    private final Map<String, Bucket> buckets = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
            return size() > MAX_TRACKED_BUCKETS;
        }
    });

    /** Takes one permit if the bucket holds one. Never blocks. */
    public Decision tryAcquire(String key, int permitsPerMinute, int burst) {
        if (permitsPerMinute <= 0) return Decision.unlimited();
        long capacity = capacity(permitsPerMinute, burst);
        long nanosPerToken = Math.max(1, NANOS_PER_MINUTE / permitsPerMinute);
        long now = System.nanoTime();

        synchronized (buckets) {
            var bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(capacity, now));
            refill(bucket, capacity, nanosPerToken, now);
            // A burst reduced by an administrator must take effect on the next call, not once the
            // oversized bucket has been drained.
            bucket.tokens = Math.min(bucket.tokens, capacity);

            if (bucket.tokens >= 1) {
                bucket.tokens--;
                return new Decision(
                        true, permitsPerMinute, bucket.tokens, 0, refillMillis(bucket, capacity, nanosPerToken, now));
            }
            long waitNanos = Math.max(0, nanosPerToken - (now - bucket.lastRefillNanos));
            return new Decision(
                    false, permitsPerMinute, 0, millis(waitNanos), refillMillis(bucket, capacity, nanosPerToken, now));
        }
    }

    /**
     * Takes one permit, waiting up to {@code maxWaitMillis} for it. Used for the provider ceiling,
     * where a short wait keeps Janus inside a quota the caller never had to know about; the client's
     * own allowance is never waited out, because that one exists to be felt.
     */
    public Decision acquire(String key, int permitsPerMinute, int burst, long maxWaitMillis) {
        long deadline = System.nanoTime() + Math.min(Math.max(0, maxWaitMillis), 60_000L) * 1_000_000L;
        while (true) {
            var decision = tryAcquire(key, permitsPerMinute, burst);
            if (decision.allowed()) return decision;
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0 || decision.waitMillis() > millis(remainingNanos)) return decision;
            try {
                Thread.sleep(Math.max(1, decision.waitMillis()));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return decision;
            }
        }
    }

    /** Drops a bucket after its policy changed, so a new allowance is not shaped by the old one. */
    public void forget(String key) {
        buckets.remove(key);
    }

    /** Drops every bucket whose key starts with the prefix, used when a provider policy changes. */
    public void forgetPrefix(String prefix) {
        synchronized (buckets) {
            buckets.keySet().removeIf(key -> key.startsWith(prefix));
        }
    }

    public void reset() {
        buckets.clear();
    }

    /** Burst defaults to a tenth of the allowance: enough to absorb a small clump, not a whole minute. */
    static long capacity(int permitsPerMinute, int burst) {
        return burst > 0 ? burst : Math.max(1, permitsPerMinute / 10);
    }

    private static void refill(Bucket bucket, long capacity, long nanosPerToken, long now) {
        long elapsed = now - bucket.lastRefillNanos;
        if (elapsed < nanosPerToken) return;
        long earned = elapsed / nanosPerToken;
        bucket.tokens = Math.min(capacity, bucket.tokens + earned);
        // Advancing by exactly what was earned keeps the fractional remainder, so a steady caller
        // at the configured rate is never slowly overcharged.
        bucket.lastRefillNanos = bucket.tokens >= capacity ? now : bucket.lastRefillNanos + earned * nanosPerToken;
    }

    private static long refillMillis(Bucket bucket, long capacity, long nanosPerToken, long now) {
        long missing = capacity - bucket.tokens;
        if (missing <= 0) return 0;
        return millis(missing * nanosPerToken - (now - bucket.lastRefillNanos));
    }

    private static long millis(long nanos) {
        return nanos <= 0 ? 0 : nanos / 1_000_000L;
    }
}
