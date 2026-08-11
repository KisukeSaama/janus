package io.janus.applications;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What somebody may state about a machine identity.
 *
 * @param allowedOrigins browser origins allowed to present this service's tokens. Omitted or empty
 *     means any, which is the default: a bearer token is what authorises a call. Their shape is
 *     checked by {@code Application#allowOrigins}, where the reason for the rule lives.
 */
public record ApplicationRequest(
        @NotBlank
                @Size(max = 120)
                @jakarta.validation.constraints.Pattern(
                        regexp = "[^\\p{Cntrl}]*",
                        message = "must not contain control characters")
                String name,
        @Size(max = 500) String description,
        boolean enabled,
        @Size(max = 10) List<@Size(max = 255) String> allowedOrigins) {

    /** Names are compared and displayed, never parsed; surrounding blanks are noise. */
    public ApplicationRequest {
        name = name == null ? null : name.trim();
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
