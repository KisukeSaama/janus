package io.janus.notifications;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import io.janus.credentials.Credential;
import io.janus.credentials.ExpiryStage;

/**
 * One announcement about one deadline.
 *
 * The sentence itself is not stored. The console renders it from the stage and the names in the
 * reader's own language, so an alert raised while one operator was reading English is not read back
 * in English by the colleague who opens the console next. What is stored is what was true when it
 * was raised: which secret, for which API, and the date that was recorded for it.
 */
@Entity
@Table(name = "notifications")
public class Notification {
    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ExpiryStage stage;
    // Held as a plain identifier rather than an association: nothing here ever needs to walk back
    // to the credential, and the database keeps the two in step by cascading the delete.
    @Column(name = "credential_id", nullable = false)
    private UUID credentialId;

    /**
     * Whose secret this is about, copied at the moment of the announcement like the names beside it.
     * It decides who sees the row in the console and who receives the mail.
     */
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "credential_name", nullable = false, length = 120)
    private String credentialName;

    @Column(name = "provider_name", nullable = false, length = 120)
    private String providerName;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    /** Reads the names now, so the announcement survives the records being renamed later. */
    public static Notification of(Credential credential, ExpiryStage stage, Instant raisedAt) {
        var notification = new Notification();
        notification.stage = stage;
        notification.credentialId = credential.getId();
        notification.credentialName = credential.getName();
        notification.providerName = credential.getProvider().getName();
        // The owner is copied; their address is not. What is being talked about is a fact of the
        // moment, but where to write is a fact of now — an address changed since is the one to use.
        notification.ownerId = credential.getProvider().getOwner().getId();
        notification.expiresAt = credential.getExpiresAt();
        notification.createdAt = raisedAt;
        return notification;
    }

    /**
     * Restates an announcement whose stage was reached again after the deadline moved. The row is
     * reused because a stage is announced once per credential; raising a second one would only give
     * the operator the same sentence twice.
     */
    public void restate(Credential credential, Instant raisedAt) {
        this.credentialName = credential.getName();
        this.providerName = credential.getProvider().getName();
        this.expiresAt = credential.getExpiresAt();
        this.createdAt = raisedAt;
        this.readAt = null;
    }

    public UUID getId() {
        return id;
    }

    public ExpiryStage getStage() {
        return stage;
    }

    public UUID getCredentialId() {
        return credentialId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getCredentialName() {
        return credentialName;
    }

    public String getProviderName() {
        return providerName;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void markRead(Instant readAt) {
        if (this.readAt == null) this.readAt = readAt;
    }
}
