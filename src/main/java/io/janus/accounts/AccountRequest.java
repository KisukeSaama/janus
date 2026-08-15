package io.janus.accounts;

import java.util.Locale;

import jakarta.validation.constraints.*;

/**
 * What an administrator may state about a person.
 *
 * @param username what they type to sign in. Set once and ignored by updates: it names the actor on
 *     every entry already written in the journal.
 * @param password optional on update, where blank means "leave it alone" — the same shape the
 *     credential secret uses, and for the same reason: Janus cannot show back what it stores.
 * @param currentPassword required only when somebody changes their own password, and ignored
 *     otherwise. An administrator resetting an account's password cannot supply it and is not asked
 *     to — the authority to do that is checked separately — but a session that has merely been left
 *     open must not be enough to replace the password that opened it.
 */
public record AccountRequest(
        @NotBlank @Size(max = 60) @Pattern(regexp = "[a-z0-9][a-z0-9._-]{0,58}[a-z0-9]")
        String username,

        @NotBlank @Size(max = 120) @Pattern(regexp = "[^\\p{Cntrl}]*", message = "must not contain control characters")
        String displayName,

        @NotBlank @Email @Size(max = 200) String email,
        @Size(max = 200) String password,
        @NotNull AccountRole role,
        boolean enabled,
        // Last in the record, so callers written before a password had to be proved still compile.
        @Size(max = 200) String currentPassword) {

    /** Compatibility overload for callers that never change their own password. */
    public AccountRequest(
            String username, String displayName, String email, String password, AccountRole role, boolean enabled) {
        this(username, displayName, email, password, role, enabled, null);
    }

    /**
     * A login is compared, never displayed as typed, so case is noise that would let "Admin" and
     * "admin" exist side by side. The same {@link Account#normalise} the sign-in applies, so what is
     * stored here is what a lookup will ask for. An address is compared the same way.
     */
    public AccountRequest {
        username = Account.normalise(username);
        displayName = displayName == null ? null : displayName.trim();
        email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
