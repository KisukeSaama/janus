package io.janus.credentials;

import java.util.*;

import org.springframework.stereotype.Component;

/**
 * The tokens Janus holds on its callers' behalf, one per credential and identity.
 *
 * <p>This is the reason a client service does not implement a clock. A token obtained for a
 * credential is reused for every call made with it, by every application that credential authorises,
 * until it is close enough to expiring to be worth replacing.
 *
 * <p>Keyed by identity as well as by credential, and that is not an optimisation. One credential now
 * holds two tokens — what the application obtained, and what a person granted — from one client id at
 * one token endpoint. Keyed by credential alone, the second to be fetched would evict the first, and
 * a call meant to go out as the application would go out as somebody in particular.
 *
 * <p>Two things are remembered, not one. A <em>failure</em> is cached too, briefly: a wrong client
 * secret would otherwise call the provider's token endpoint once per proxied request, which is how a
 * misconfiguration turns into an outbound flood and, at some providers, a block. Same idea as
 * {@code UpstreamCooldown}, at the scale of one credential.
 */
@Component
public class UpstreamTokenCache {
    /**
     * Replaced this long before the announced expiry. A token that expires in flight fails a call
     * that had no reason to fail, and providers are not precise to the second.
     */
    static final long SAFETY_MARGIN_SECONDS = 60;

    /** What is used when a provider states no lifetime. Conservative: renewing early costs one call. */
    static final long ASSUMED_LIFETIME_SECONDS = 300;

    /** How long a refused exchange is remembered, so a bad secret is not retried on every request. */
    static final long FAILURE_COOLDOWN_SECONDS = 30;

    private static final int MAX_ENTRIES = 5_000;

    /** A held token, or the memory of a refusal. Never both, and never the reason for the refusal. */
    private record Entry(String token, int failureStatus, long usableUntilNanos) {
        boolean failed() {
            return token == null;
        }
    }

    /** One credential's tokens are two entries, told apart by whom each speaks for. */
    private record Key(UUID credentialId, Identity identity) {}

    private final Map<Key, Entry> entries = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        // Qualified deliberately. Inside a LinkedHashMap subclass the simple name Entry resolves to the
        // inherited Map.Entry rather than to the record above, and the override stops overriding.
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, UpstreamTokenCache.Entry> eldest) {
            return size() > MAX_ENTRIES;
        }
    });

    /** A token still far enough from its expiry to be worth sending. */
    public Optional<String> lookup(UUID credentialId, Identity identity) {
        var entry = live(new Key(credentialId, identity));
        return entry == null || entry.failed() ? Optional.empty() : Optional.of(entry.token());
    }

    /** The status of a recent refusal, while its cooldown lasts. Never the provider's response body. */
    public OptionalInt recentFailure(UUID credentialId, Identity identity) {
        var entry = live(new Key(credentialId, identity));
        return entry != null && entry.failed() ? OptionalInt.of(entry.failureStatus()) : OptionalInt.empty();
    }

    /**
     * Holds a token for the lifetime the provider stated, less the safety margin.
     *
     * @param expiresInSeconds what the provider said, or null when it said nothing
     */
    public void store(UUID credentialId, Identity identity, String token, Long expiresInSeconds) {
        long usable = usableSeconds(expiresInSeconds);
        entries.put(new Key(credentialId, identity), new Entry(token, 0, System.nanoTime() + usable * 1_000_000_000L));
    }

    /**
     * How long a token is worth reusing, given what the provider said about it.
     *
     * <p>Never zero: a provider that announces a lifetime shorter than the margin is telling us the
     * token is nearly spent, and holding it for a moment still spares the calls arriving right now
     * from each performing their own exchange.
     */
    static long usableSeconds(Long expiresInSeconds) {
        long lifetime = expiresInSeconds == null ? ASSUMED_LIFETIME_SECONDS : expiresInSeconds;
        return Math.max(1, lifetime - SAFETY_MARGIN_SECONDS);
    }

    public void storeFailure(UUID credentialId, Identity identity, int status) {
        entries.put(
                new Key(credentialId, identity),
                new Entry(null, status, System.nanoTime() + FAILURE_COOLDOWN_SECONDS * 1_000_000_000L));
    }

    /**
     * Forgets what is held for this credential, both identities together. Called when the stored
     * secret changes, because the token it produced belongs to the previous secret and may well
     * outlive it upstream.
     */
    public void invalidate(UUID credentialId) {
        synchronized (entries) {
            entries.keySet().removeIf(key -> key.credentialId().equals(credentialId));
        }
    }

    /** Forgets one identity's token, leaving the other's. Used when a call is refused with it. */
    public void invalidate(UUID credentialId, Identity identity) {
        entries.remove(new Key(credentialId, identity));
    }

    public void clear() {
        entries.clear();
    }

    private Entry live(Key key) {
        var entry = entries.get(key);
        if (entry == null) return null;
        if (System.nanoTime() - entry.usableUntilNanos() >= 0) {
            entries.remove(key, entry);
            return null;
        }
        return entry;
    }
}
