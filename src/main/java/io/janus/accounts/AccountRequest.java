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
 */
public record AccountRequest(
        @NotBlank @Size(max = 60) @Pattern(regexp = "[a-z0-9][a-z0-9._-]{0,58}[a-z0-9]") String username,
        @NotBlank @Size(max = 120) @Pattern(regexp = "[^\\p{Cntrl}]*", message = "must not contain control characters")
                String displayName,
        @NotBlank @Email @Size(max = 200) String email,
        @Size(max = 200) String password,
        @NotNull AccountRole role,
        boolean enabled) {

    /**
     * A login is compared, never displayed as typed, so case is noise that would let "Admin" and
     * "admin" exist side by side. An address is compared the same way.
     */
    public AccountRequest {
        username = username == null ? null : username.trim().toLowerCase(Locale.ROOT);
        displayName = displayName == null ? null : displayName.trim();
        email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
