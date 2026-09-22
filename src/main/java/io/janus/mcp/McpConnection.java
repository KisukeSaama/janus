package io.janus.mcp;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

/**
 * A consent given: one client, acting for one account, until the account holder takes it back.
 *
 * <p>It holds the hashes of the two tokens currently in the client's hands. A refresh replaces both in
 * place, so the console lists one line per assistant somebody let in rather than one per token ever
 * issued, and revoking that line revokes everything the assistant holds.
 */
@Entity
@Table(name = "mcp_connections")
public class McpConnection {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    private McpClient client;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "access_token_hash", nullable = false, length = 64)
    private String accessTokenHash;

    @Column(name = "access_expires_at", nullable = false)
    private Instant accessExpiresAt;

    @Column(name = "refresh_token_hash", nullable = false, length = 64)
    private String refreshTokenHash;

    /** The refresh token this one replaced: seeing it again means it leaked. */
    @Column(name = "previous_refresh_hash", length = 64)
    private String previousRefreshHash;

    @Column(name = "refresh_expires_at", nullable = false)
    private Instant refreshExpiresAt;

    @Column(name = "authorized_at", nullable = false)
    private Instant authorizedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    /** For Hibernate only. */
    protected McpConnection() {}

    public McpConnection(McpClient client, UUID accountId) {
        this.id = UUID.randomUUID();
        this.client = client;
        this.accountId = accountId;
        this.authorizedAt = Instant.now();
    }

    /** Hands out a new pair. The refresh token being replaced is remembered, never honoured again. */
    public void issue(String accessHash, Instant accessExpiresAt, String refreshHash, Instant refreshExpiresAt) {
        this.previousRefreshHash = this.refreshTokenHash;
        this.accessTokenHash = accessHash;
        this.accessExpiresAt = accessExpiresAt;
        this.refreshTokenHash = refreshHash;
        this.refreshExpiresAt = refreshExpiresAt;
    }

    /**
     * Records use, at most once a minute. The console shows it to help somebody tell a live assistant
     * from a forgotten one, and that question does not need a write on every call.
     */
    public boolean touch(Instant now) {
        if (lastUsedAt != null && lastUsedAt.isAfter(now.minusSeconds(60))) return false;
        lastUsedAt = now;
        return true;
    }

    public UUID getId() {
        return id;
    }

    public McpClient getClient() {
        return client;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public Instant getAccessExpiresAt() {
        return accessExpiresAt;
    }

    public Instant getRefreshExpiresAt() {
        return refreshExpiresAt;
    }

    public Instant getAuthorizedAt() {
        return authorizedAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }
}
