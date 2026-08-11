package io.janus.grants;

import java.util.UUID;

import jakarta.validation.constraints.*;

/**
 * What an administrator may state about one application's access to one provider.
 *
 * @param rateLimitPerMinute what this application may ask of this provider per minute; 0 is no ceiling
 * @param rateLimitBurst how much of that allowance may be spent at once; 0 derives a tenth of it
 */
public record GrantRequest(
        @NotNull UUID applicationId,
        @NotNull UUID providerId,
        @NotNull UUID credentialId,
        boolean enabled,
        @Min(0) @Max(1000000) Integer rateLimitPerMinute,
        @Min(0) @Max(100000) Integer rateLimitBurst) {

    public Grant.Quota quota() {
        return new Grant.Quota(orZero(rateLimitPerMinute), orZero(rateLimitBurst));
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
