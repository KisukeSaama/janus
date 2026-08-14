package io.janus.credentials;

/**
 * How Janus presents the application's own credential to the API it is calling.
 *
 * <p>Between them these cover what a developer actually meets. The first is the absence of the
 * others. The four after it present the stored value directly and differ only in where they put it.
 * The last two are different in kind, because the stored value is not what travels — it is exchanged
 * for something short-lived, or it signs the request rather than accompanying it.
 *
 * <p>This says nothing about the second identity a destination may offer, the one belonging to a
 * person who connected their account. That was a value here once, which is what made an API with both
 * identities impossible to register as one destination; it is {@code Provider.Connection} now, set
 * beside whichever of these the application itself uses.
 *
 * <p>The list is deliberately short. Every strategy here is one a developer meets; the ones
 * considered and left out — an assertion signed with a private key, a key split across two headers —
 * belong to enterprise service accounts rather than to public APIs, and including them would have made
 * the common case harder to find in order to serve a case most deployments never meet.
 *
 * <p>The order is the order the console offers them in, which is roughly how often a developer meets
 * them, and it is not an implementation detail — {@code ordinal()} is never persisted or compared.
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
     * bearer token that Janus caches and renews before it expires. This is how an application reads
     * what an API publishes to everyone: Spotify's catalogue, Twitch's streams, Reddit's listings.
     */
    OAUTH2_CLIENT_CREDENTIALS,
    /**
     * The stored value is {@code key:secret}, and the secret signs each request rather than travelling
     * with it. Exchanges work this way — Binance, Coinbase, Kraken — and a developer who meets one
     * meets nothing else that resembles it.
     *
     * <p>Last in the list, and the only one here that asks a reader to know how their API composes the
     * string it signs: there is no agreed canonicalisation, so the recipe is recorded per destination
     * rather than assumed. See {@link SignatureTemplate}.
     */
    HMAC_SIGNATURE;

    /**
     * Whether the stored value has to be turned into something else at a token endpoint before
     * anything can be sent upstream.
     */
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

    /**
     * Whether the stored secret signs the outbound request instead of travelling in it. Applied last,
     * after the target URI and the body are both settled, since it covers them.
     */
    public boolean signs() {
        return this == HMAC_SIGNATURE;
    }

    /**
     * Whether the stored value holds two things separated by a colon. Worth naming because the
     * validation is identical for all of them and only the console's wording differs.
     */
    public boolean paired() {
        return this == BASIC || this == OAUTH2_CLIENT_CREDENTIALS || this == HMAC_SIGNATURE;
    }
}
