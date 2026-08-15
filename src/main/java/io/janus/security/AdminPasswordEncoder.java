package io.janus.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;

import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Remembers a verified administrator password for a few minutes, so the console does not pay a
 * BCrypt cost-12 comparison on every request.
 *
 * <p>The gateway already works this way — see {@link ApiKeyCache}, and the reason given there
 * applies unchanged here. HTTP Basic is stateless, so without this every single console request
 * costs the same deliberate tenth of a second the hash was tuned to take: several per screen for an
 * operator, and, more to the point, a multiplier on anything sent in bulk. A rate limit that admits
 * five requests a second admits nearly a core of hashing with them.
 *
 * <p>Only successful comparisons are remembered, so guessing fills nothing. Entries are keyed on a
 * digest of the presented value together with the hash it was checked against, never on the value
 * itself, which is what makes a changed password invalidate its own entries: accounts live in the
 * database and a password is replaced without restarting anything, so the new hash simply does not
 * match a key built from the old one. The time-to-live bounds how long a hash that was deleted
 * rather than replaced stays remembered.
 *
 * <p>Wired to the administrator realm alone, by {@code SecurityConfig}. The encoder that mints and
 * checks application keys stays the plain one: those have their own cache, invalidated on rotation.
 */
public class AdminPasswordEncoder implements PasswordEncoder {
    private static final Duration TTL = Duration.ofMinutes(5);
    /** One account, one password: the bound exists to cap memory, not to hold a working set. */
    private static final int MAX_ENTRIES = 64;

    private final PasswordEncoder delegate;
    private final Map<String, Long> verified = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > MAX_ENTRIES;
        }
    });

    public AdminPasswordEncoder(PasswordEncoder delegate) {
        this.delegate = delegate;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null) return delegate.matches(rawPassword, encodedPassword);

        String key = key(rawPassword, encodedPassword);
        Long expiresAtNanos = verified.get(key);
        if (expiresAtNanos != null) {
            if (System.nanoTime() - expiresAtNanos < 0) return true;
            verified.remove(key);
        }

        boolean matched = delegate.matches(rawPassword, encodedPassword);
        if (matched) verified.put(key, System.nanoTime() + TTL.toNanos());
        return matched;
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return delegate.upgradeEncoding(encodedPassword);
    }

    public void clear() {
        verified.clear();
    }

    /** Both halves are digested together, so an entry cannot outlive the hash it was verified against. */
    private static String key(CharSequence rawPassword, String encodedPassword) {
        try {
            var sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(encodedPassword.getBytes(StandardCharsets.UTF_8));
            sha256.update((byte) 0);
            sha256.update(rawPassword.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha256.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }
}
