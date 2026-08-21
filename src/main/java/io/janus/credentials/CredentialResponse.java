package io.janus.credentials;

import java.time.Instant;
import java.util.UUID;

/**
 * A stored secret as the console sees it.
 *
 * @param secretRef an {@code openbao://} reference, never the value; nothing in this API can read a
 *     secret back out of OpenBao. Absent for an anonymous credential, which has nothing stored at its
 *     path: a reference shown for it would name a location holding nothing.
 * @param awaitingAuthorization whether somebody still has to agree at the provider before this can be
 *     used. Its own field rather than an inference from {@code authorizedAt}, because it is the one
 *     thing the console acts on: it turns a row into a button.
 * @param authorizedSubject whom the provider says the stored consent belongs to. Displayed so an
 *     operator can tell whose account a connection speaks for, and never sent anywhere.
 * @param connectionAwaitingSecret whether the connection still needs an OAuth client of its own
 *     before anyone can be asked
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
        String clientIdHeader,
        String connectionAuthorizationUrl,
        String connectionTokenUrl,
        String connectionScopes,
        TokenClientAuth connectionClientAuth,
        boolean connectionAwaitingSecret,
        SignatureAlgorithm signatureAlgorithm,
        String signatureTemplate,
        SignatureEncoding signatureEncoding,
        String signatureHeader,
        String signatureParameter,
        String timestampHeader,
        String timestampParameter,
        String secretRef,
        boolean enabled,
        boolean awaitingAuthorization,
        Instant authorizedAt,
        String authorizedSubject,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {

    public static CredentialResponse of(Credential credential) {
        var signature = credential.signatureSettings();
        var connection = credential.connection();
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
                credential.getClientIdHeader(),
                connection.authorizationUrl(),
                connection.tokenUrl(),
                connection.scopes(),
                connection.clientAuth(),
                credential.offersConnection() && !credential.connectionUsable(),
                credential.getSignatureAlgorithm(),
                signature == null ? null : signature.template().pattern(),
                signature == null ? null : signature.encoding(),
                signature == null ? null : signature.signatureHeader(),
                signature == null ? null : signature.signatureParameter(),
                signature == null ? null : signature.timestampHeader(),
                signature == null ? null : signature.timestampParameter(),
                credential.getAuthType().anonymous() ? null : "openbao://" + credential.getSecretPath(),
                credential.isEnabled(),
                credential.awaitingAuthorization(),
                credential.getAuthorizedAt(),
                credential.getAuthorizedSubject(),
                credential.getExpiresAt(),
                credential.getCreatedAt(),
                credential.getUpdatedAt());
    }
}
