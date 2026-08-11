package io.janus.security;

import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import io.janus.applications.Application;
import io.janus.applications.ApplicationRepository;

/**
 * Proving that a caller holds an application's key, in one place.
 *
 * <p>There are two doors now — the key presented on every request, and the token exchange that takes
 * the same key once — and both have to behave identically: an unknown identifier still pays a hash
 * comparison so response timing discloses nothing, and a verified key is cached because a BCrypt
 * cost-12 comparison per call is a denial-of-service surface. Written twice, those two properties
 * drift; one of the two copies eventually skips the decoy, and the difference is measurable.
 */
@Component
public class ApplicationAuthenticator {
    private final ApplicationRepository applications;
    private final PasswordEncoder encoder;
    private final ApiKeyCache keyCache;
    /** Hash of an unguessable value; comparing against it equalises timing for unknown applications. */
    private final String decoyHash;

    public ApplicationAuthenticator(ApplicationRepository applications, PasswordEncoder encoder, ApiKeyCache keyCache) {
        this.applications = applications;
        this.encoder = encoder;
        this.keyCache = keyCache;
        byte[] material = new byte[32];
        new java.security.SecureRandom().nextBytes(material);
        this.decoyHash = encoder.encode(Base64.getEncoder().encodeToString(material));
    }

    /** Looks the application up and verifies the key, or answers empty without saying which failed. */
    public Optional<GatewayPrincipal> authenticate(UUID applicationId, String presentedKey) {
        if (applicationId == null || presentedKey == null || presentedKey.isEmpty()) return Optional.empty();
        return verify(applications.findByIdWithOwner(applicationId).orElse(null), applicationId, presentedKey);
    }

    /** For callers that already hold the row — the request filter, which loaded it to reject early. */
    public Optional<GatewayPrincipal> verify(Application application, UUID applicationId, String presentedKey) {
        if (application == null || !application.isEnabled()) {
            encoder.matches(presentedKey, decoyHash);
            return Optional.empty();
        }
        String storedHash = application.getApiKeyHash();
        var cached = keyCache.lookup(applicationId, presentedKey, storedHash);
        if (cached.isPresent()) return cached;
        if (!encoder.matches(presentedKey, storedHash)) return Optional.empty();

        var principal = new GatewayPrincipal(
                application.getId(),
                application.getName(),
                application.getOwner().getId(),
                application.getOwner().getUsername(),
                application.getAllowedOrigins());
        keyCache.store(applicationId, presentedKey, storedHash, principal);
        return Optional.of(principal);
    }
}
