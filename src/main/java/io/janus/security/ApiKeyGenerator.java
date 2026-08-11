package io.janus.security;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Issues the keys client applications present at the gateway.
 *
 * <p>A key is 256 bits of randomness shown exactly once. Janus keeps only a BCrypt hash of it, so a
 * lost key is replaced rather than recovered. The {@code jns_} prefix makes a leaked key
 * recognisable in a log or a secret scanner as belonging to this system.
 */
@Component
public class ApiKeyGenerator {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PREFIX = "jns_";
    private static final int ENTROPY_BYTES = 32;

    private final PasswordEncoder encoder;

    public ApiKeyGenerator(PasswordEncoder encoder) {
        this.encoder = encoder;
    }

    /** A freshly issued key and the hash to store for it. */
    public record IssuedKey(String value, String hash) {}

    public IssuedKey issue() {
        byte[] material = new byte[ENTROPY_BYTES];
        RANDOM.nextBytes(material);
        String value = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(material);
        return new IssuedKey(value, encoder.encode(value));
    }
}
