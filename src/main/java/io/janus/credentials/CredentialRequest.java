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
 *     For a client-credentials exchange it is the pair {@code client_id:client_secret}, and for
 *     {@code NONE} there is none to give.
 * @param expiresAt when the upstream key stops working, as an instant rather than a date: the console
 *     holds the operator's calendar day and Janus holds the moment, so a deployment and its reader
 *     never disagree about which day that was. Optional — a key with no known end is never announced.
 *     For an exchange, this is the end of the <em>client secret</em>, not of the tokens it produces:
 *     those last minutes, are never persisted, and are renewed by Janus without anyone being told.
 * @param tokenUrl where client credentials are exchanged, for {@code OAUTH2_CLIENT_CREDENTIALS}
 * @param tokenScopes space separated, as RFC 6749 says; blank means the client's default scopes
 * @param tokenClientAuth how Janus presents itself there; defaults to Basic, which the spec requires
 *     every server to accept
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
        @Size(min = 1, max = 8192) String secret,
        Instant expiresAt,
        boolean enabled) {

    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9-]{1,100}");
    /** A query parameter travels in a URL, so anything needing encoding to survive is refused. */
    private static final Pattern QUERY_PARAMETER = Pattern.compile("[A-Za-z0-9._~-]{1,100}");

    public CredentialRequest {
        name = name == null ? null : name.trim();
    }

    public boolean carriesSecret() {
        return secret != null && !secret.isBlank();
    }

    public Credential.Strategy strategy() {
        return new Credential.Strategy(authType, headerName, queryParameter, tokenUrl, tokenScopes, tokenClientAuth);
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
            case BASIC -> {
                if (carriesSecret() && secret.indexOf(':') < 0)
                    throw new IllegalArgumentException("Basic credentials must be supplied as username:password");
            }
            case OAUTH2_CLIENT_CREDENTIALS -> {
                if (tokenUrl == null || tokenUrl.isBlank())
                    throw new IllegalArgumentException(
                            "A token endpoint is required, such as https://accounts.spotify.com/api/token");
                // Same shape as Basic, and for the same reason: two values, one stored string.
                if (carriesSecret() && secret.indexOf(':') < 0)
                    throw new IllegalArgumentException(
                            "Client credentials must be supplied as client_id:client_secret");
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
    }
}
