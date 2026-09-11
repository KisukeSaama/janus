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
 * @param allowAccountIdentity whether it may speak for the connected account; absent is yes, which
 *     is what every grant written before the question was asked already does
 * @param graphqlOperations the GraphQL operation types it may send; empty is all of them
 * @param graphqlRootFields the GraphQL root fields its operations may select; empty is all of them
 */
public record GrantRequest(
        @NotNull UUID applicationId,
        @NotNull UUID providerId,
        @NotNull UUID credentialId,
        boolean enabled,
        @Min(0) @Max(1000000) Integer rateLimitPerMinute,
        @Min(0) @Max(100000) Integer rateLimitBurst,
        @Size(max = 512) String pathPrefix,
        List<@Size(max = 10) String> methods,
        // Boxed rather than primitive: a caller written before this field existed omits it, and the
        // answer for an omitted field here is "yes" rather than the false a boolean would default to.
        Boolean allowAccountIdentity,
        // Last, so callers written before a grant could narrow a GraphQL API still compile. Absent
        // means everything, like the path prefix and the methods.
        @Size(max = 3) List<@Size(max = 20) String> graphqlOperations,
        @Size(max = 50) List<@Size(max = 100) String> graphqlRootFields) {

    /** Compatibility overload for callers written before a grant could refuse the account identity. */
    public GrantRequest(
            UUID applicationId,
            UUID providerId,
            UUID credentialId,
            boolean enabled,
            Integer rateLimitPerMinute,
            Integer rateLimitBurst,
            String pathPrefix,
            List<String> methods) {
        this(
                applicationId,
                providerId,
                credentialId,
                enabled,
                rateLimitPerMinute,
                rateLimitBurst,
                pathPrefix,
                methods,
                null,
                null,
                null);
    }

    public Grant.Quota quota() {
        return new Grant.Quota(orZero(rateLimitPerMinute), orZero(rateLimitBurst));
    }

    /**
     * Absent means the whole destination, which is what a grant that says nothing has always meant.
     * The parts are read together so that omitting all of them is the one and only default.
     */
    public GrantScope scope() {
        return GrantScope.of(
                pathPrefix,
                joined(methods),
                allowAccountIdentity == null || allowAccountIdentity,
                joined(graphqlOperations),
                joined(graphqlRootFields));
    }

    private static String joined(List<String> values) {
        return values == null ? null : String.join(",", values);
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
