package io.janus.gateway;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import io.janus.credentials.Identity;

/**
 * Which endpoints turned out to want the account's token rather than the application's.
 *
 * <p>Nobody configures this. The first call to an endpoint that refuses the application identity
 * costs one extra round trip; what it buys is a note, and every call after it goes out right the
 * first time. A note that stops being true, because an API changed or a scope was granted, is
 * corrected the same way it was made, by the endpoint refusing what was presented.
 *
 * <p>In memory and per instance, like {@link RateLimiter} and {@link UpstreamCooldown}. Restarting
 * costs one replay per endpoint per instance, which is a price worth a great deal less than a write
 * on the request path; the counters would move to a shared store together if any of them ever do.
 *
 * <p>Bounded in the same way and for the same reason as the rate limiter's buckets: this is keyed in
 * part by paths a caller chooses, so it must not be something a caller can grow without limit.
 *
 * <p><b>Nothing here takes a lock on the request path.</b> This is read once per proxied call, on
 * every proxied call, so an exact least-recently-used order would mean every read mutating one
 * shared structure and every read queueing behind the others, which is a lock acquired for a hint.
 * What is held instead is two generations: what has been reached recently, and what had been reached
 * before that. Reads consult the first, fall back to the second and promote what they find there;
 * when the first fills, it becomes the second and the one it displaces is dropped whole. That bounds
 * the total, keeps the entries worth keeping, and costs a plain concurrent map lookup.
 */
@Component
public class IdentityMemory {
    static final int MAX_TRACKED_ROUTES = 50_000;

    /** How large the current generation may grow before it displaces the older one. */
    private static final int GENERATION = MAX_TRACKED_ROUTES / 2;

    /** What has been reached since the last rotation, and what had been reached before it. */
    private volatile ConcurrentHashMap<String, Identity> recent = new ConcurrentHashMap<>(256);

    private volatile ConcurrentHashMap<String, Identity> older = new ConcurrentHashMap<>(256);

    /** Held only while generations are swapped, which is never on a read. */
    private final Object rotation = new Object();

    /** What was learned for this endpoint, or empty when it has never been reached. */
    public Optional<Identity> recall(UUID credentialId, String method, String decodedPath) {
        String key = key(credentialId, method, decodedPath);
        var learned = recent.get(key);
        if (learned != null) return Optional.of(learned);

        // Reached again after a rotation, so it belongs with what is current: promoting it is what
        // keeps a busy endpoint from being dropped by the rotation after next. Moved rather than
        // copied, so that promoting never grows the total: the ceiling below is on both generations.
        learned = older.get(key);
        if (learned != null && older.remove(key, learned)) recent.putIfAbsent(key, learned);
        return Optional.ofNullable(learned);
    }

    public void remember(UUID credentialId, String method, String decodedPath, Identity identity) {
        // Checked before the write, not after: rotating afterwards would let the generation settle one
        // entry above its ceiling every time, and the ceiling is the whole point of having one.
        if (recent.size() >= GENERATION) rotate();
        recent.put(key(credentialId, method, decodedPath), identity);
    }

    /**
     * Drops everything learned for a credential. Called when a connection is authorised or revoked:
     * what an endpoint answered while nobody had agreed says nothing about what it answers now.
     *
     * <p>A scan of both generations, and it does not stop anybody reading: a concurrent map's removal
     * is entry by entry, so a call arriving during one of these is answered from whatever it has
     * reached rather than waiting for it to finish. The worst a caller sees is the note this is in the
     * middle of removing, which costs it the replay this exists to save and nothing else.
     */
    public void forget(UUID credentialId) {
        String prefix = credentialId + " ";
        recent.keySet().removeIf(key -> key.startsWith(prefix));
        older.keySet().removeIf(key -> key.startsWith(prefix));
    }

    public void reset() {
        synchronized (rotation) {
            recent = new ConcurrentHashMap<>(256);
            older = new ConcurrentHashMap<>(256);
        }
    }

    /** How many notes are held across both generations. Package-private: the bound is what is tested. */
    int tracked() {
        return recent.size() + older.size();
    }

    /**
     * The current generation becomes the older one, and what the older one held is dropped.
     *
     * <p>Checked again under the lock, because several writers may have crossed the threshold together
     * and rotating twice would throw away a generation that had just been promoted into.
     */
    private void rotate() {
        synchronized (rotation) {
            if (recent.size() < GENERATION) return;
            older = recent;
            recent = new ConcurrentHashMap<>(256);
        }
    }

    /**
     * Keyed by credential, not by provider: two accounts calling the same API may have granted
     * different scopes, so an endpoint one of them may reach with the application's token is one the
     * other may not.
     */
    private static String key(UUID credentialId, String method, String decodedPath) {
        return credentialId + " " + method + " " + RouteTemplate.of(decodedPath);
    }
}
