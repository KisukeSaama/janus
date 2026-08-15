package io.janus.grants;

import java.time.Instant;
import java.util.List;
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
        String pathPrefix,
        List<String> methods,
        boolean allowAccountIdentity,
        Instant createdAt,
        Instant updatedAt) {

    public static GrantResponse of(Grant grant) {
        var scope = grant.getScope();
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
                scope.pathPrefix(),
                // A list rather than the stored string: the console offers one checkbox per method,
                // and nothing outside this class should have to know how a column spells a set.
                scope.orderedMethods(),
                scope.admitsAccountIdentity(),
                grant.getCreatedAt(),
                grant.getUpdatedAt());
    }
}
