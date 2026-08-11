package io.janus.providers;

import jakarta.validation.constraints.*;

/**
 * What an administrator may state about a destination.
 *
 * <p>The traffic fields are boxed because omitting them is meaningful: an unstated policy takes the
 * documented default rather than zero. They are normalised here, once, so no caller has to.
 *
 * @param cacheEnabled omitted means true: Janus reuses whatever the upstream says is reusable
 * @param cacheTtlSeconds freshness to assume when the upstream states none; 0 leaves it to the upstream
 * @param rateLimitPerMinute outbound ceiling for this destination, all callers together; 0 is no ceiling
 * @param rateLimitBurst how much of that allowance may be spent at once; 0 derives a tenth of it
 */
public record ProviderRequest(
        // Displayed, and carried into the body of the expiry mail. A control character in a name is
        // either a mistake or a header injection; neither has a use here.
        @NotBlank @Size(max = 120) @Pattern(regexp = "[^\\p{Cntrl}]*", message = "must not contain control characters")
                String name,
        @NotBlank
                @Pattern(
                        regexp = "[a-z0-9][a-z0-9-]{1,78}[a-z0-9]",
                        message = "must be lowercase letters, digits, and hyphens")
                String slug,
        @NotBlank @Size(max = 500) String baseUrl,
        boolean enabled,
        Boolean cacheEnabled,
        @Min(0) @Max(86400) Integer cacheTtlSeconds,
        @Min(0) @Max(1000000) Integer rateLimitPerMinute,
        @Min(0) @Max(100000) Integer rateLimitBurst) {

    public ProviderRequest {
        name = name == null ? null : name.trim();
    }

    public Provider.TrafficPolicy trafficPolicy() {
        return new Provider.TrafficPolicy(
                cacheEnabled == null || cacheEnabled,
                orZero(cacheTtlSeconds),
                orZero(rateLimitPerMinute),
                orZero(rateLimitBurst));
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
