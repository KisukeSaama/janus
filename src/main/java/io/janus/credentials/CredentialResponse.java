package io.janus.credentials;

import java.time.Instant;
import java.util.UUID;

/**
 * A stored secret as the console sees it.
 *
 * @param secretRef an {@code openbao://} reference, never the value; nothing in this API can read a
 *     secret back out of OpenBao. Absent for an anonymous credential, which has nothing stored at its
 *     path: a reference shown for it would name a location holding nothing.
 */
public record CredentialResponse(
        UUID id,
        String name,
        UUID providerId,
        String providerName,
        AuthType authType,
        String headerName,
        String queryParameter,
        String tokenUrl,
        String tokenScopes,
        TokenClientAuth tokenClientAuth,
        String secretRef,
        boolean enabled,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {

    public static CredentialResponse of(Credential credential) {
        return new CredentialResponse(
                credential.getId(),
                credential.getName(),
                credential.getProvider().getId(),
                credential.getProvider().getName(),
                credential.getAuthType(),
                credential.getHeaderName(),
                credential.getQueryParameter(),
                credential.getTokenUrl(),
                credential.getTokenScopes(),
                credential.getTokenClientAuth(),
                credential.getAuthType().anonymous() ? null : "openbao://" + credential.getSecretPath(),
                credential.isEnabled(),
                credential.getExpiresAt(),
                credential.getCreatedAt(),
                credential.getUpdatedAt());
    }
}
