package io.janus.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

import org.springframework.stereotype.Component;

import io.janus.security.GatewayPrincipal;

/**
 * The bearer tokens Janus has issued and not yet forgotten.
 *
 * <p>In memory, per instance, and deliberately so: an access token lives for minutes, and writing a
 * row per issued token to buy survival across a restart trades a durable write on every exchange for
 * a cost that is one extra exchange. What must survive a restart is the refresh token, and that one
 * is in the database.
 *
 * <p>Opaque rather than signed. A JWT would need no store at all, but it is also honoured until it
 * expires no matter what happens in between — disabling an application, rotating its key, handing it
 * to somebody else. Here, revocation is immediate, which is the property the console promises.
 *
 * <p>The value is never held: entries are keyed by the SHA-256 of the token, so a heap dump yields
 * nothing that can be presented. Same rule as {@code ApiKeyCache}, for the same reason.
 */
@Component
public class AccessTokenStore {
    public static final String PREFIX = "jnt_";
    private static final int ENTROPY_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final int maxEntries;

    /** What was granted, and until when. Nanos, so a clock adjustment cannot extend a token. */
    private record Grant(GatewayPrincipal principal, long expiresAtNanos) {}

    private final Map<String, Grant> issued;

    public AccessTokenStore(OAuthProperties properties) {
        this.maxEntries = properties.maxActiveTokens();
        this.issued = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Grant> eldest) {
                return size() > maxEntries;
            }
        });
    }

    /** Mints a token for this caller and answers the value, which is the only time it exists. */
    public String issue(GatewayPrincipal principal, long ttlSeconds) {
        byte[] material = new byte[ENTROPY_BYTES];
        RANDOM.nextBytes(material);
        String token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(material);
        issued.put(digest(token), new Grant(principal, System.nanoTime() + ttlSeconds * 1_000_000_000L));
        return token;
    }

    /** The caller behind a bearer token, or nothing if it is unknown, expired, or revoked. */
    public Optional<GatewayPrincipal> resolve(String token) {
        if (token == null || token.isEmpty()) return Optional.empty();
        String key = digest(token);
        var grant = issued.get(key);
        if (grant == null) return Optional.empty();
        if (System.nanoTime() - grant.expiresAtNanos() >= 0) {
            issued.remove(key, grant);
            return Optional.empty();
        }
        return Optional.of(grant.principal());
    }

    public boolean revoke(String token) {
        return token != null && issued.remove(digest(token)) != null;
    }

    /**
     * Drops every token issued to one application. Called when what the token stands for stops being
     * true — the key rotated, the application was disabled, deleted, or handed to somebody else.
     */
    public int revokeApplication(UUID applicationId) {
        synchronized (issued) {
            int before = issued.size();
            issued.values().removeIf(grant -> grant.principal().applicationId().equals(applicationId));
            return before - issued.size();
        }
    }

    public void clear() {
        issued.clear();
    }

    static String digest(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the platform", ex);
        }
    }
}
