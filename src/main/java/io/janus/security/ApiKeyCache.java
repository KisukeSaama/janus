package io.janus.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;

import org.springframework.stereotype.Component;

/**
 * Remembers recently verified application keys so the gateway does not pay a BCrypt cost-12
 * verification (hundreds of milliseconds) on every proxied request.
 *
 * <p>Only successful verifications are cached, and never the key itself: entries are addressed by a
 * SHA-256 digest of the presented key and pinned to the stored BCrypt hash, so a rotated key cannot
 * be served from a stale entry. Administrative mutations invalidate explicitly; the time-to-live is
 * a backstop for changes made outside this instance.
 */
@Component
public class ApiKeyCache {
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final int MAX_ENTRIES = 10_000;

    private record Entry(String storedHash, GatewayPrincipal principal, long expiresAtNanos) {}

    private final Map<String, Entry> entries = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > MAX_ENTRIES;
        }
    });

    /** Returns the cached principal when the presented key was already verified against {@code storedHash}. */
    public Optional<GatewayPrincipal> lookup(UUID applicationId, String presentedKey, String storedHash) {
        var entry = entries.get(key(applicationId, presentedKey));
        if (entry == null) return Optional.empty();
        if (System.nanoTime() - entry.expiresAtNanos() >= 0 || !constantTimeEquals(entry.storedHash(), storedHash)) {
            entries.remove(key(applicationId, presentedKey));
            return Optional.empty();
        }
        return Optional.of(entry.principal());
    }

    public void store(UUID applicationId, String presentedKey, String storedHash, GatewayPrincipal principal) {
        entries.put(
                key(applicationId, presentedKey), new Entry(storedHash, principal, System.nanoTime() + TTL.toNanos()));
    }

    /** Drops every entry for an application after it is updated, key-rotated, or deleted. */
    public void invalidate(UUID applicationId) {
        String prefix = applicationId + ":";
        synchronized (entries) {
            entries.keySet().removeIf(k -> k.startsWith(prefix));
        }
    }

    public void clear() {
        entries.clear();
    }

    private static String key(UUID applicationId, String presentedKey) {
        return applicationId + ":" + digest(presentedKey);
    }

    private static String digest(String value) {
        try {
            var sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
