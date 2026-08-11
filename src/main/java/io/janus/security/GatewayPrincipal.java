package io.janus.security;

import java.util.Set;
import java.util.UUID;

/**
 * The authenticated caller behind a gateway request.
 *
 * <p>It carries the owner as well as the application, because a provider slug only names a
 * destination within its owner's namespace: two people may each register {@code spotify}, and it is
 * the calling application's owner that says which one {@code /gateway/spotify/...} means. The owner
 * comes from the application row the filter has already loaded, so it costs no extra query — but it
 * does mean a transfer of ownership has to drop the cached key, as every other edit already does.
 *
 * @param allowedOrigins browser origins this caller's tokens may be presented from; empty means any,
 *     which is the default. Carried here so the check costs no query, which also means an edit has
 *     to drop what is cached — the verified key and any issued access token.
 */
public record GatewayPrincipal(UUID applicationId, String applicationName, UUID ownerId, Set<String> allowedOrigins) {

    public GatewayPrincipal {
        allowedOrigins = allowedOrigins == null ? Set.of() : Set.copyOf(allowedOrigins);
    }

    public GatewayPrincipal(UUID applicationId, String applicationName, UUID ownerId) {
        this(applicationId, applicationName, ownerId, Set.of());
    }

    /**
     * Whether a browser at this origin may use this caller's credentials.
     *
     * <p>A request without an {@code Origin} did not come from a browser — a server, a script, a cron
     * — and there is nothing to check: origins restrict pages, not machines.
     */
    public boolean allowsOrigin(String origin) {
        return origin == null || allowedOrigins.isEmpty() || allowedOrigins.contains(origin);
    }
}
