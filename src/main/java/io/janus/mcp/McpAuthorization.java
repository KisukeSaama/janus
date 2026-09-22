package io.janus.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

/**
 * A consent in progress: asked for by a client, not yet redeemed.
 *
 * <p>Two stages in one row. Until somebody agrees, the row is a request the console can describe; once
 * they do, it carries the account that agreed and the hash of the code handed to the client. Either
 * way it is read once and deleted, and it lasts minutes: long enough to sign in and read a consent
 * screen, not long enough to be worth stealing.
 */
@Entity
@Table(name = "mcp_authorizations")
public class McpAuthorization {
    /** How long somebody has to sign in and decide. */
    public static final Duration REQUEST_LIFETIME = Duration.ofMinutes(10);

    /** How long the client has to redeem a code, which it does the moment its browser comes back. */
    public static final Duration CODE_LIFETIME = Duration.ofMinutes(2);

    @Id
    @Column(length = 64)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    private McpClient client;

    /** Kept as it was sent: RFC 6749 §4.1.3 requires the token request to repeat it exactly. */
    @Column(name = "redirect_uri", nullable = false, length = 500)
    private String redirectUri;

    @Column(name = "code_challenge", nullable = false, length = 128)
    private String codeChallenge;

    @Column(length = 500)
    private String state;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "code_hash", length = 64)
    private String codeHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** For Hibernate only. */
    protected McpAuthorization() {}

    public McpAuthorization(String id, McpClient client, String redirectUri, String codeChallenge, String state) {
        this.id = id;
        this.client = client;
        this.redirectUri = redirectUri;
        this.codeChallenge = codeChallenge;
        this.state = state;
        this.createdAt = Instant.now();
        this.expiresAt = createdAt.plus(REQUEST_LIFETIME);
    }

    /** Somebody agreed. The clock restarts, shorter: what is left is a machine redeeming a code. */
    public void approve(UUID accountId, String codeHash) {
        this.accountId = accountId;
        this.codeHash = codeHash;
        this.expiresAt = Instant.now().plus(CODE_LIFETIME);
    }

    public boolean decided() {
        return codeHash != null;
    }

    public boolean expired() {
        return !Instant.now().isBefore(expiresAt);
    }

    public String getId() {
        return id;
    }

    public McpClient getClient() {
        return client;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public String getCodeChallenge() {
        return codeChallenge;
    }

    public String getState() {
        return state;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
