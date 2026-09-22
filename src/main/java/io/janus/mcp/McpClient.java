package io.janus.mcp;

import java.time.Instant;
import java.util.*;

import jakarta.persistence.*;

/**
 * A program that registered itself to ask for a consent (RFC 7591).
 *
 * <p>Nobody is signed in when one registers, so nothing here is vouched for. The name is what the
 * program calls itself, and the console says so where it shows it. The redirect addresses are the one
 * thing that matters: a code is only ever sent to one of them, which is what keeps a consent given for
 * one program from being collected by another.
 */
@Entity
@Table(name = "mcp_clients")
public class McpClient {
    @Id
    private UUID id;

    @Column(name = "client_name", nullable = false, length = 120)
    private String clientName;

    @Column(name = "redirect_uris", nullable = false, length = 2600)
    private String redirectUris;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** For Hibernate only. */
    protected McpClient() {}

    public McpClient(String clientName, List<String> redirectUris) {
        this.id = UUID.randomUUID();
        this.clientName = clientName;
        this.redirectUris = String.join("\n", redirectUris);
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getClientName() {
        return clientName;
    }

    public List<String> redirectUris() {
        return List.of(redirectUris.split("\n"));
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
