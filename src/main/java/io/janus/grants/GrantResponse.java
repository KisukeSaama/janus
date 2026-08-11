package io.janus.grants;

import java.time.Instant;
import java.util.UUID;

/** One application's access to one provider, as the console sees it. */
public record GrantResponse(
        UUID id,
        UUID applicationId,
        String applicationName,
        UUID providerId,
        String providerName,
        UUID credentialId,
        String credentialName,
        boolean enabled,
        int rateLimitPerMinute,
        int rateLimitBurst,
        Instant createdAt,
        Instant updatedAt) {

    public static GrantResponse of(Grant grant) {
        return new GrantResponse(
                grant.getId(),
                grant.getApplication().getId(),
                grant.getApplication().getName(),
                grant.getProvider().getId(),
                grant.getProvider().getName(),
                grant.getCredential().getId(),
                grant.getCredential().getName(),
                grant.isEnabled(),
                grant.getRateLimitPerMinute(),
                grant.getRateLimitBurst(),
                grant.getCreatedAt(),
                grant.getUpdatedAt());
    }
}
