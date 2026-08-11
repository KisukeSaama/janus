package io.janus.openbao;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

/** A value no database record references anymore, retained until OpenBao destroys it. */
@Entity
@Table(name = "pending_secret_deletions")
class PendingSecretDeletion {
    @Id
    private UUID id;

    @Column(name = "secret_path", nullable = false, unique = true, length = 500)
    private String secretPath;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** For Hibernate only. */
    protected PendingSecretDeletion() {}

    PendingSecretDeletion(String secretPath) {
        this.id = UUID.randomUUID();
        this.secretPath = secretPath;
        this.createdAt = Instant.now();
    }

    UUID getId() {
        return id;
    }

    String getSecretPath() {
        return secretPath;
    }
}
