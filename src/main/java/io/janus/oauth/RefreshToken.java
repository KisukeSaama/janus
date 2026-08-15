package io.janus.oauth;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

/**
 * A client's right to come back for a new bearer token without presenting its secret again.
 *
 * <p>One row per issued token, holding its SHA-256 rather than the value. Using a token retires its
 * row and issues a successor in the same family, so a value that is presented twice is evidence that
 * it leaked — the second presentation is refused and the whole family is dropped, which is the
 * standard reuse-detection behaviour and the reason {@code familyId} exists.
 *
 * <p>No association to the application: this is machinery rather than a record somebody manages, and
 * the row is removed with the application by the database. The identifier is enough to load it.
 */
@Entity
@Table(name = "application_refresh_tokens")
public class RefreshToken {
    @Id
    private UUID id;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    /** For Hibernate only. */
    protected RefreshToken() {}

    public RefreshToken(UUID applicationId, String tokenHash, UUID familyId, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.issuedAt = Instant.now();
        this.applicationId = applicationId;
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.expiresAt = expiresAt;
    }

    /**
     * Whether this token has been retired.
     *
     * <p>Read only. Retiring one is {@code RefreshTokenRepository.markUsed}, which does it
     * conditionally so that two requests presenting the same value cannot both succeed; setting the
     * field here would put that decision back in a place where the check and the write are two
     * steps with a gap between them.
     */
    public boolean spent() {
        return usedAt != null;
    }

    public boolean expired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }
}
