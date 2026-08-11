package io.janus.accounts;

import java.time.Instant;
import java.util.UUID;

/**
 * A person as the console sees them. There is no field for the password, in any shape: Janus holds a
 * one-way hash and has nothing to show.
 */
public record AccountResponse(
        UUID id,
        String username,
        String displayName,
        String email,
        AccountRole role,
        boolean enabled,
        Instant passwordChangedAt,
        Instant lastSignedInAt,
        Instant createdAt,
        Instant updatedAt) {

    public static AccountResponse of(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getUsername(),
                account.getDisplayName(),
                account.getEmail(),
                account.getRole(),
                account.isEnabled(),
                account.getPasswordChangedAt(),
                account.getLastSignedInAt(),
                account.getCreatedAt(),
                account.getUpdatedAt());
    }
}
