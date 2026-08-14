package io.janus.grants;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.*;

/**
 * What an administrator may state about one application's access to one provider.
 *
 * @param rateLimitPerMinute what this application may ask of this provider per minute; 0 is no ceiling
 * @param rateLimitBurst how much of that allowance may be spent at once; 0 derives a tenth of it
 * @param pathPrefix the path under which this application may call; empty is the whole destination
 * @param methods the methods it may use; empty is all of them
 */
public record GrantRequest(
        @NotNull UUID applicationId,
        @NotNull UUID providerId,
        @NotNull UUID credentialId,
        boolean enabled,
        @Min(0) @Max(1000000) Integer rateLimitPerMinute,
        @Min(0) @Max(100000) Integer rateLimitBurst,
        @Size(max = 512) String pathPrefix,
        List<@Size(max = 10) String> methods) {

    public Grant.Quota quota() {
        return new Grant.Quota(orZero(rateLimitPerMinute), orZero(rateLimitBurst));
    }

    /**
     * Absent means the whole destination, which is what a grant that says nothing has always meant.
     * The two halves are read together so that omitting both is the one and only default.
     */
    public GrantScope scope() {
        return GrantScope.of(pathPrefix, methods == null ? null : String.join(",", methods));
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
