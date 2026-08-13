package io.janus.security;

import java.time.Duration;
import java.util.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Slows down credential guessing against both the administrator Basic realm and the gateway API-key
 * headers. Counters are per instance and deliberately in-memory: a shared store is required before
 * running more than one replica, which the README records as a production prerequisite.
 */
@Component
public class AuthenticationThrottle {
    private static final int MAX_TRACKED_CLIENTS = 50_000;

    private record Attempts(int failures, long windowStartNanos, long blockedUntilNanos) {}

    private final int maxFailures;
    private final long windowNanos;
    private final long blockNanos;
    private final Map<String, Attempts> attempts = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Attempts> eldest) {
            return size() > MAX_TRACKED_CLIENTS;
        }
    });

    public AuthenticationThrottle(
            @Value("${janus.security.max-auth-failures:10}") int maxFailures,
            @Value("${janus.security.auth-failure-window-seconds:300}") long windowSeconds,
            @Value("${janus.security.auth-block-seconds:900}") long blockSeconds) {
        this.maxFailures = maxFailures;
        this.windowNanos = Duration.ofSeconds(windowSeconds).toNanos();
        this.blockNanos = Duration.ofSeconds(blockSeconds).toNanos();
    }

    public boolean isBlocked(String client) {
        return blockedForSeconds(client) > 0;
    }

    /**
     * How much longer this client stays blocked, or 0 when it is not.
     *
     * <p>Exists so a refusal can carry {@code Retry-After}. A block lasts fifteen minutes by default,
     * and a caller that is not told that reasonably assumes its credentials are simply wrong — or
     * keeps retrying, which is the behaviour the block was meant to stop. Rounded up, never to zero
     * while the block stands, so obeying the header always clears it.
     */
    public long blockedForSeconds(String client) {
        var current = attempts.get(client);
        if (current == null || current.blockedUntilNanos() == 0) return 0;
        long remaining = current.blockedUntilNanos() - System.nanoTime();
        return remaining <= 0 ? 0 : Math.max(1, Duration.ofNanos(remaining).toSeconds() + 1);
    }

    public void recordFailure(String client) {
        long now = System.nanoTime();
        attempts.compute(client, (key, current) -> {
            boolean windowExpired = current == null || now - current.windowStartNanos() >= windowNanos;
            int failures = windowExpired ? 1 : current.failures() + 1;
            long windowStart = windowExpired ? now : current.windowStartNanos();
            long previousBlock = windowExpired ? 0 : current.blockedUntilNanos();
            return new Attempts(failures, windowStart, failures >= maxFailures ? now + blockNanos : previousBlock);
        });
    }

    public void recordSuccess(String client) {
        attempts.remove(client);
    }

    public void reset() {
        attempts.clear();
    }
}
