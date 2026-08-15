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
    private final int maxPerApplication;

    /** What was granted, and until when. Nanos, so a clock adjustment cannot extend a token. */
    private record Grant(GatewayPrincipal principal, long expiresAtNanos) {}

    /** Access order, so the iteration below runs from least recently used towards most. */
    private final Map<String, Grant> issued = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true));

    public AccessTokenStore(OAuthProperties properties) {
        this.maxEntries = properties.maxActiveTokens();
        this.maxPerApplication = properties.applicationTokenCeiling();
    }

    /** Mints a token for this caller and answers the value, which is the only time it exists. */
    public String issue(GatewayPrincipal principal, long ttlSeconds) {
        byte[] material = new byte[ENTROPY_BYTES];
        RANDOM.nextBytes(material);
        String token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(material);
        synchronized (issued) {
            issued.put(digest(token), new Grant(principal, System.nanoTime() + ttlSeconds * 1_000_000_000L));
            enforceBounds(principal.applicationId());
        }
        return token;
    }

    /**
     * Keeps the store inside its bounds, and takes what it has to take from whoever filled it.
     *
     * <p>A single least-recently-used ceiling over the whole deployment made one application's
     * traffic everybody else's problem: an application holding valid credentials and asking for
     * tokens in a loop — well inside its own rate limit — evicts every other application's tokens in
     * minutes, and each of those callers finds itself unauthenticated for no reason it can see.
     *
     * <p>So three steps, in order of what they cost. Anything expired goes first and costs nobody
     * anything. Then the caller's own ceiling, which is the step that makes a loop bounded by the
     * loop rather than by the store. Only what is left over is taken from the whole, and by then a
     * deployment reaching that point has more live callers than it was configured for, which is a
     * figure to raise rather than a caller to blame.
     *
     * <p>One pass for the sweep and the count together: the store is walked on every exchange, and a
     * token lasts minutes, so this is once per caller per quarter of an hour rather than per request.
     */
    private void enforceBounds(UUID applicationId) {
        long now = System.nanoTime();
        int held = 0;
        var entries = issued.values().iterator();
        while (entries.hasNext()) {
            var grant = entries.next();
            if (now - grant.expiresAtNanos() >= 0) entries.remove();
            else if (grant.principal().applicationId().equals(applicationId)) held++;
        }
        dropOldest(
                held - maxPerApplication,
                grant -> grant.principal().applicationId().equals(applicationId));
        dropOldest(issued.size() - maxEntries, grant -> true);
    }

    /** Drops the {@code excess} least recently used entries among those the predicate admits. */
    private void dropOldest(int excess, java.util.function.Predicate<Grant> among) {
        if (excess <= 0) return;
        var entries = issued.values().iterator();
        while (excess > 0 && entries.hasNext()) {
            if (among.test(entries.next())) {
                entries.remove();
                excess--;
            }
        }
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
