package io.janus.credentials;

/**
 * How Janus presents a stored secret to the API it is calling.
 *
 * <p>Between them these cover what a developer actually meets. The first is the absence of the
 * others. The four after it present the stored value directly and differ only in where they put it.
 * The last two are different in kind, because the stored value is not what travels — it is exchanged
 * for something short-lived first, and Janus holds that exchange so no client service has to.
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
     * The same exchange, for data belonging to a person rather than to the application.
     *
     * <p>This is the difference between the Spotify catalogue and somebody's playlists, and no amount
     * of configuration substitutes for it: the provider will only issue these tokens once a person has
     * agreed, at the provider's own site, to this application acting for them.
     *
     * <p>What is stored is {@code client_id:client_secret} and, once somebody has agreed, the refresh
     * token their consent produced. Janus holds the redirect, the {@code state}, the PKCE verifier and
     * the callback, so a client service never sees any of it — it asks for a playlist, and Janus
     * decides which token that needs and whether it is still good.
     *
     * <p>Until consent is given there is nothing to send, and the credential says so rather than
     * failing upstream: a missing authorisation is a state the console can show and a person can fix
     * in one click, not an error a caller should be handed.
     */
    OAUTH2_AUTHORIZATION_CODE,
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
     * anything can be sent upstream. Both produce a bearer token; they differ in what they present to
     * get it, and in whether a person had to agree first.
     */
    public boolean exchanged() {
        return this == OAUTH2_CLIENT_CREDENTIALS || this == OAUTH2_AUTHORIZATION_CODE;
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
     * Whether a person has to agree at the provider's own site before this can be used. The one
     * strategy that cannot be made to work by an administrator typing a value into a form.
     */
    public boolean consented() {
        return this == OAUTH2_AUTHORIZATION_CODE;
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
        return this == BASIC
                || this == OAUTH2_CLIENT_CREDENTIALS
                || this == OAUTH2_AUTHORIZATION_CODE
                || this == HMAC_SIGNATURE;
    }
}
