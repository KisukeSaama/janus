package io.janus.credentials;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * How Janus proves who it is at an upstream token endpoint.
 *
 * <p>RFC 6749 §2.3.1 requires a server to accept HTTP Basic and permits it to accept the credentials
 * in the form body. Providers disagree about which they actually support — Spotify takes both,
 * others only one — so this is recorded rather than assumed.
 */
public enum TokenClientAuth {
    /** {@code Authorization: Basic base64(client_id:client_secret)}. The default, and the one the spec requires. */
    BASIC,
    /** {@code client_id} and {@code client_secret} as form fields, for the servers that want them there. */
    POST;

    /**
     * The value that follows {@code Basic}, built the way RFC 6749 §2.3.1 says to build it.
     *
     * <p>Both halves are form-urlencoded <em>before</em> they are joined and encoded, which is the
     * step that is easy to miss because it changes nothing for the great majority of secrets. It
     * changes everything for the ones containing {@code +}, {@code /}, {@code =} or a space —
     * base64-shaped secrets, which is what a good many providers issue — and a server that follows
     * the spec decodes what it receives and finds a different secret than the one that was stored.
     * The symptom is an intermittent {@code invalid_client} that depends on which secret was minted,
     * which is close to impossible to read from the outside.
     */
    public static String basicCredentials(String clientId, String clientSecret) {
        String pair = formEncode(clientId) + ":" + formEncode(clientSecret);
        return Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

    private static String formEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
