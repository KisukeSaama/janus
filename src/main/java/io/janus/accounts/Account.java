package io.janus.accounts;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

/**
 * A person who signs in to the console, and — from V8 — the owner of the records they create.
 *
 * <p>The username is set once and never changes. It is what somebody types to sign in and what the
 * journal shows beside an action, so renaming it would rewrite the meaning of entries already
 * written. Everything else about a person is editable; who they are is not.
 *
 * <p>Identity and timestamps are assigned in the constructor rather than by a callback, for the
 * reason given at length on {@code Application}: with an assigned identifier Hibernate defers the
 * insert to flush time, and a callback-populated {@code createdAt} is still null when the response
 * to a create is written.
 */
@Entity
@Table(name = "accounts")
public class Account {
    @Id
    private UUID id;

    @Column(nullable = false, length = 60)
    private String username;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(nullable = false, length = 200)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountRole role;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "password_changed_at", nullable = false)
    private Instant passwordChangedAt;

    @Column(name = "last_signed_in_at")
    private Instant lastSignedInAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** For Hibernate only. */
    protected Account() {}

    public Account(
            String username, String displayName, String email, String passwordHash, AccountRole role, boolean enabled) {
        this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.passwordChangedAt = this.createdAt;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        describe(displayName, email, enabled);
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * Package-private, and the only exception to the rule above: the bootstrap row is created by a
     * migration under the name {@code admin}, then the startup reconciler gives it the fixed
     * bootstrap name. This also lets an existing deployment adopt that name.
     *
     * <p>The display name follows while it is still the login, which is what {@code V14} writes:
     * a shared bootstrap account names nobody, and renaming one of the two would invent a person
     * called after whoever the login used to be.
     */
    void rename(String username) {
        if (this.username.equals(displayName)) this.displayName = username;
        this.username = username;
    }

    /** Everything an administrator may change about a person, other than their role. */
    public void describe(String displayName, String email, boolean enabled) {
        this.displayName = displayName;
        this.email = email;
        this.enabled = enabled;
    }

    /**
     * Replacing the hash is what ages a password, so the timestamp moves with it and never
     * separately — the same rule the API keys follow.
     */
    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
        this.passwordChangedAt = Instant.now();
    }

    public void assignRole(AccountRole role) {
        this.role = role;
    }

    /**
     * True while this account holds an unusable placeholder hash. The bootstrap row is posted by the
     * migration that creates the table, because ownership becomes NOT NULL before any account could
     * have been created from the console; it is the startup reconciler that gives it a password.
     */
    public boolean awaitingBootstrap() {
        return BOOTSTRAP_HASH.equals(passwordHash);
    }

    /** Matches the value inserted by {@code V7__accounts.sql}; not a BCrypt hash, so nothing matches it. */
    static final String BOOTSTRAP_HASH = "!";

    public static final String BOOTSTRAP_USERNAME = "kisuke";
    public static final UUID BOOTSTRAP_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public AccountRole getRole() {
        return role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getPasswordChangedAt() {
        return passwordChangedAt;
    }

    public Instant getLastSignedInAt() {
        return lastSignedInAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
