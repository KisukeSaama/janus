package io.janus.applications;

import java.time.Instant;
import java.util.*;

/**
 * A machine identity as the console sees it.
 *
 * @param apiKeyRotatedAt when the key currently in the application's hands was issued; it is key age
 *     rather than registration date that says whether a rotation is overdue
 * @param allowedOrigins browser origins allowed to present this service's tokens; empty means any
 */
public record ApplicationResponse(
        UUID id,
        String name,
        String description,
        boolean enabled,
        List<String> allowedOrigins,
        Instant apiKeyRotatedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static ApplicationResponse of(Application application) {
        return new ApplicationResponse(
                application.getId(),
                application.getName(),
                application.getDescription(),
                application.isEnabled(),
                List.copyOf(application.getAllowedOrigins()),
                application.getApiKeyRotatedAt(),
                application.getCreatedAt(),
                application.getUpdatedAt());
    }
}
