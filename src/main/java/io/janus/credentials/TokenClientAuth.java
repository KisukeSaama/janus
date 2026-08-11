package io.janus.credentials;

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
    POST
}
