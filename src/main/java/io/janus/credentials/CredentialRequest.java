package io.janus.credentials;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.validation.constraints.*;

/**
 * What an administrator may state about a stored secret.
 *
 * @param secret the value itself, accepted on create and on replacement only. It is written straight
 *     to OpenBao and never stored here; leaving it blank on an update keeps what OpenBao already holds.
 *     For the strategies that hold two things it is a colon-separated pair, and for {@code NONE} there
 *     is none to give.
 * @param expiresAt when the upstream key stops working, as an instant rather than a date: the console
 *     holds the operator's calendar day and Janus holds the moment, so a deployment and its reader
 *     never disagree about which day that was. Optional — a key with no known end is never announced.
 *     For an exchange, this is the end of the <em>client secret</em>, not of the tokens it produces:
 *     those last minutes, are never persisted, and are renewed by Janus without anyone being told.
 * @param tokenUrl where the application's credentials are exchanged, for the strategy that exchanges
 *     them
 * @param tokenScopes space separated, as RFC 6749 says; blank means the client's default scopes
 * @param tokenClientAuth how Janus presents itself there; defaults to Basic, which the spec requires
 *     every server to accept
 * @param connectionSecret the OAuth client an account connection exchanges with, when the API does
 *     not already store one for the application itself
 */
public record CredentialRequest(
        // A name is displayed, and it also travels in the subject line of the expiry mail. A control
        // character in it is either a mistake or a header injection; neither has a use here.
        @NotBlank
        @Size(max = 120)
        @jakarta.validation.constraints.Pattern(
                regexp = "[^\\p{Cntrl}]*",
                message = "must not contain control characters")
        String name,

        @NotNull UUID providerId,
        @NotNull AuthType authType,
        @Size(max = 100) String headerName,
        @Size(max = 100) String queryParameter,
        @Size(max = 500) String tokenUrl,
        @Size(max = 500) String tokenScopes,
        TokenClientAuth tokenClientAuth,
        SignatureAlgorithm signatureAlgorithm,
        @Size(max = SignatureTemplate.MAX_LENGTH) String signatureTemplate,
        SignatureEncoding signatureEncoding,
        @Size(max = 100) String signatureHeader,
        @Size(max = 100) String signatureParameter,
        @Size(max = 100) String timestampHeader,
        @Size(max = 100) String timestampParameter,
        // Refused where it is typed rather than where it is used. A stored secret travels in an
        // outgoing header for most strategies, and a control character in one is either a mistake or
        // a header injection. Netty refuses the value on the way out either way, so without this the
        // failure surfaces on the first proxied call, on a screen that cannot say which field caused
        // it — the same reasoning as `name` above, one layer later.
        @Size(min = 1, max = 8192)
        @jakarta.validation.constraints.Pattern(
                regexp = "[^\\p{Cntrl}]*",
                message = "must not contain control characters")
        String secret,

        Instant expiresAt,
        boolean enabled,
        // The OAuth client the account connection exchanges with, when it is not the one above. Left
        // empty whenever the two are the same client — which is every API that issues one client id
        // and mints both kinds of token from it. Last in the record, so callers written before an API
        // could offer two identities still compile.
        @Size(min = 1, max = 8192)
        @jakarta.validation.constraints.Pattern(
                regexp = "[^\\p{Cntrl}]*",
                message = "must not contain control characters")
        String connectionSecret,
        // The header an exchange puts its client id on, copied from the API. Last in the record, so
        // callers written before it still compile.
        @Size(max = 100) String clientIdHeader) {

    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9-]{1,100}");
    /** A query parameter travels in a URL, so anything needing encoding to survive is refused. */
    private static final Pattern QUERY_PARAMETER = Pattern.compile("[A-Za-z0-9._~-]{1,100}");

    public CredentialRequest {
        name = name == null ? null : name.trim();
    }

    /** Compatibility overload for callers written before consent and signing were offered. */
    public CredentialRequest(
            String name,
            UUID providerId,
            AuthType authType,
            String headerName,
            String queryParameter,
            String tokenUrl,
            String tokenScopes,
            TokenClientAuth tokenClientAuth,
            String secret,
            Instant expiresAt,
            boolean enabled) {
        this(
                name,
                providerId,
                authType,
                headerName,
                queryParameter,
                tokenUrl,
                tokenScopes,
                tokenClientAuth,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                secret,
                expiresAt,
                enabled,
                null,
                null);
    }

    public boolean carriesSecret() {
        return secret != null && !secret.isBlank();
    }

    public boolean carriesConnectionSecret() {
        return connectionSecret != null && !connectionSecret.isBlank();
    }

    public Credential.Strategy strategy() {
        return new Credential.Strategy(
                authType,
                headerName,
                queryParameter,
                tokenUrl,
                tokenScopes,
                tokenClientAuth,
                signature(),
                clientIdHeader);
    }

    /** The signing recipe, or null when this strategy does not sign. */
    public SignatureSettings signature() {
        if (!authType.signs()) return null;
        return new SignatureSettings(
                signatureAlgorithm,
                signatureTemplate == null || signatureTemplate.isBlank()
                        ? null
                        : new SignatureTemplate(signatureTemplate),
                signatureEncoding,
                signatureHeader,
                signatureParameter,
                timestampHeader,
                timestampParameter);
    }

    /**
     * Rules that involve the secret itself, and so cannot live on the entity. Checked before anything
     * is written, on both create and update.
     */
    public void validate() {
        switch (authType) {
            case API_KEY_HEADER -> {
                if (headerName == null || !HEADER_NAME.matcher(headerName).matches())
                    throw new IllegalArgumentException("A valid header name is required for a header API key");
            }
            case API_KEY_QUERY -> {
                if (queryParameter == null
                        || !QUERY_PARAMETER.matcher(queryParameter).matches())
                    throw new IllegalArgumentException(
                            "A valid query parameter name is required, such as apikey or access_token");
            }
            case BASIC -> requirePair("Basic credentials must be supplied as username:password");
            case OAUTH2_CLIENT_CREDENTIALS -> {
                if (tokenUrl == null || tokenUrl.isBlank())
                    throw new IllegalArgumentException(
                            "A token endpoint is required, such as https://accounts.spotify.com/api/token");
                requirePair("Client credentials must be supplied as client_id:client_secret");
                // It is written onto every outbound request, so it is checked like any other header
                // name rather than trusted because the console filled it in.
                if (clientIdHeader != null
                        && !clientIdHeader.isBlank()
                        && !HEADER_NAME.matcher(clientIdHeader).matches())
                    throw new IllegalArgumentException("A valid header name is required for the client id");
            }
            case HMAC_SIGNATURE -> {
                requirePair("Signing credentials must be supplied as key:secret");
                // The recipe is validated as a whole, since no part of it means anything alone.
                var settings = signature();
                if (settings == null || settings.template() == null)
                    throw new IllegalArgumentException(
                            "A signing recipe is required, such as " + SignatureTemplate.TIMESTAMP_METHOD_PATH_BODY);
                settings.validate();
                if (headerName != null && !HEADER_NAME.matcher(headerName).matches())
                    throw new IllegalArgumentException("A valid header name is required for the key");
            }
            case BEARER -> {
                /* the stored value travels as it is */
            }
            // Refused rather than ignored: a value accepted here would be written to OpenBao and
            // never sent anywhere, which is the one outcome an operator would not expect from
            // having typed it.
            case NONE -> {
                if (carriesSecret())
                    throw new IllegalArgumentException("An open API takes no secret; choose how the key is sent");
            }
        }
        // Whatever the application presents, a connection exchanges as an OAuth client. Whether one
        // belongs here at all depends on the API, which this record cannot see; CredentialService
        // refuses a value for a destination that offers no connection.
        if (carriesConnectionSecret() && connectionSecret.indexOf(':') < 0)
            throw new IllegalArgumentException(
                    "The account connection's credentials must be supplied as client_id:client_secret");
    }

    /** Two values in one stored string, checked only when a value was actually supplied. */
    private void requirePair(String message) {
        if (carriesSecret() && secret.indexOf(':') < 0) throw new IllegalArgumentException(message);
    }
}
