package io.janus.credentials;

/**
 * How Janus presents a stored secret to the API it is calling.
 *
 * <p>Between them these cover what a developer actually meets. The first is the absence of the
 * others. The four after it present the stored value directly; the last one is different in kind,
 * because the stored value is not what travels — it is exchanged for something short-lived first,
 * and Janus holds that exchange so no client service has to.
 */
public enum AuthType {
    /**
     * Nothing is presented, because there is nothing to present: no value is written to OpenBao and
     * none is read when a request goes out. Public APIs work this way, and so do the internal ones
     * that trust the network they sit on.
     *
     * <p>Everything else Janus does for a destination still applies — the route allowlist, both
     * allowances, the shared cache, the journal — which is the reason to route an open API through it
     * at all.
     */
    NONE,
    /** {@code Authorization: Bearer <secret>}. */
    BEARER,
    /** The secret in a named header, whatever the API decided to call it. */
    API_KEY_HEADER,
    /** The secret as a query parameter — {@code ?apikey=…} and its many spellings. */
    API_KEY_QUERY,
    /** {@code Authorization: Basic …}; the stored value is {@code username:password}. */
    BASIC,
    /**
     * The stored value is {@code client_id:client_secret}, exchanged at a token endpoint for a
     * bearer token that Janus caches and renews before it expires. Spotify, Reddit, Twitch and most
     * of the modern platforms work this way.
     */
    OAUTH2_CLIENT_CREDENTIALS;

    /** Whether the stored value has to be exchanged before anything can be sent upstream. */
    public boolean exchanged() {
        return this == OAUTH2_CLIENT_CREDENTIALS;
    }

    /** Whether there is a stored value at all. Nothing reads OpenBao for one of these. */
    public boolean anonymous() {
        return this == NONE;
    }

    /** Whether the secret travels in the URL rather than in a header. */
    public boolean inQuery() {
        return this == API_KEY_QUERY;
    }
}
