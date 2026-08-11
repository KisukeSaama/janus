package io.janus.credentials;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

/**
 * An authorisation in progress: what Janus needs to remember between sending somebody to a provider
 * and their coming back.
 *
 * <p>The row exists because the two halves of the flow are separate HTTP requests made by a browser
 * that visited somebody else's site in between. Nothing may be inferred when it returns — which
 * credential this was for, what secret was used to start it, whether the person who came back is the
 * one who left — so all of it is written down first and read once.
 *
 * <p>The state itself is the only thing that travels, and it is what the callback is trusted on. It
 * is a random 256-bit value, valid for minutes, usable once. That is deliberate rather than
 * incidental: a browser returning from a provider is a cross-site navigation, so the console's
 * session cookie is not sent with it, and the callback has nothing else to go on.
 */
@Entity
@Table(name = "oauth_authorization_states")
public class AuthorizationState {
    /** How long somebody has to read a consent screen and decide. */
    public static final java.time.Duration LIFETIME = java.time.Duration.ofMinutes(15);

    @Id
    @Column(name = "state", length = 64)
    private String state;

    @Column(name = "credential_id", nullable = false)
    private UUID credentialId;

    /** Who started it, recorded so the consent is attributed to an account rather than to a browser. */
    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    /** The PKCE secret (RFC 7636), which proves the code is being redeemed by whoever asked for it. */
    @Column(name = "code_verifier", nullable = false, length = 128)
    private String codeVerifier;

    /**
     * The redirect that started the flow. Recorded rather than rebuilt: RFC 6749 §4.1.3 requires the
     * one sent to the token endpoint to be identical to it, and a deployment's public URL can change
     * between the two requests.
     */
    @Column(name = "redirect_uri", nullable = false, length = 500)
    private String redirectUri;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** For Hibernate only. */
    protected AuthorizationState() {}

    public AuthorizationState(
            String state, UUID credentialId, UUID accountId, String codeVerifier, String redirectUri) {
        this.state = state;
        this.credentialId = credentialId;
        this.accountId = accountId;
        this.codeVerifier = codeVerifier;
        this.redirectUri = redirectUri;
        this.createdAt = Instant.now();
        this.expiresAt = this.createdAt.plus(LIFETIME);
    }

    public String getState() {
        return state;
    }

    public UUID getCredentialId() {
        return credentialId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getCodeVerifier() {
        return codeVerifier;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean expired() {
        return Instant.now().isAfter(expiresAt);
    }
}
