package io.janus.accounts;

import java.util.*;

import org.springframework.security.core.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The signed-in person, as the rest of Janus sees them.
 *
 * <p>It carries the account's identifier, not only its name: {@code AccessScope} decides what a
 * request may see from it, and the journal attributes an action to it. Both would otherwise need a
 * database round trip on every call to turn a login back into an account.
 *
 * <p>Not a record, deliberately. {@link CredentialsContainer} exists so the password hash is dropped
 * once authentication has succeeded, and the console's principal outlives the request — it is held
 * in the session. A record could not erase anything.
 */
public class ConsoleUser implements UserDetails, CredentialsContainer {
    private final UUID id;
    private final String username;
    private final String displayName;
    private final AccountRole role;
    private final boolean enabled;
    private String passwordHash;

    public ConsoleUser(Account account) {
        this.id = account.getId();
        this.username = account.getUsername();
        this.displayName = account.getDisplayName();
        this.role = account.getRole();
        this.enabled = account.isEnabled();
        this.passwordHash = account.getPasswordHash();
    }

    public UUID id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public AccountRole role() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void eraseCredentials() {
        this.passwordHash = null;
    }
}
