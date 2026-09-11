package io.janus.gateway.graphql;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * The documents behind the persisted-query hashes Janus has been shown, so a client sending only a
 * hash can still be read.
 *
 * <p>Automatic persisted queries send a SHA-256 in place of the document once the server has seen it.
 * Janus cannot decide anything about a hash, so it keeps what it verified: a hash arrives alongside
 * its document once, is checked against it, and is known from then on. A hash Janus does not know is
 * answered the way any APQ server answers one, which makes the client send the document again; that
 * is the protocol working as designed rather than a failure.
 *
 * <p>Keyed by destination, because two APIs are two servers and a hash one of them knows says nothing
 * about the other. Bounded, in two generations, for the same reasons and in the same way as
 * {@code IdentityMemory}: the keys are chosen by callers, and reads must not take a lock.
 */
@Component
public class PersistedQueries {
    /** Documents longer than this are verified and forwarded, and not kept. */
    static final int MAX_DOCUMENT_CHARS = 100_000;

    private final int generation;
    private volatile ConcurrentHashMap<String, String> recent = new ConcurrentHashMap<>(256);
    private volatile ConcurrentHashMap<String, String> older = new ConcurrentHashMap<>(256);
    private final Object rotation = new Object();

    public PersistedQueries(GraphQlProperties properties) {
        this.generation = Math.max(1, properties.persistedQueries() / 2);
    }

    /** The document verified for this hash at this destination, or null when none was. */
    String recall(UUID providerId, String hash) {
        String key = key(providerId, hash);
        String document = recent.get(key);
        if (document != null) return document;
        document = older.get(key);
        if (document != null && older.remove(key, document)) recent.putIfAbsent(key, document);
        return document;
    }

    /** Called only once the hash has been checked against the document it arrived with. */
    void remember(UUID providerId, String hash, String document) {
        if (document.length() > MAX_DOCUMENT_CHARS) return;
        if (recent.size() >= generation) rotate();
        recent.put(key(providerId, hash), document);
    }

    public void forget(UUID providerId) {
        String prefix = providerId + " ";
        recent.keySet().removeIf(key -> key.startsWith(prefix));
        older.keySet().removeIf(key -> key.startsWith(prefix));
    }

    int tracked() {
        return recent.size() + older.size();
    }

    private void rotate() {
        synchronized (rotation) {
            if (recent.size() < generation) return;
            older = recent;
            recent = new ConcurrentHashMap<>(256);
        }
    }

    private static String key(UUID providerId, String hash) {
        return providerId + " " + hash;
    }
}
