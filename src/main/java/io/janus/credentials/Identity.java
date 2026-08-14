package io.janus.credentials;

import java.util.Locale;

/**
 * Whom a request speaks for.
 *
 * <p>One destination now holds both. An API publishes one address and issues one client id, and that
 * client obtains two different tokens: one the application owns, and one a person granted it. Spotify
 * calls the first its catalogue and the second somebody's playlists, and offers no way to tell from a
 * URL which of the two an endpoint wants.
 *
 * <p>So Janus does not try to read it from the URL. It presents {@link #APP} first — the identity
 * whose answers can be shared between every caller — and remembers the endpoints that refused it. See
 * {@code IdentityMemory}.
 */
public enum Identity {
    /** The application's own token, or its key, or nothing at all when the API is open. */
    APP,
    /** The token a person granted at the provider's site, held for whoever holds the credential. */
    ACCOUNT;

    /** What a caller writes in {@code X-Janus-Identity}, and what a response states it used. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The identity a caller asked for, or {@code null} when it stated nothing — which is the ordinary
     * case, and the one the memory exists to answer. An unrecognised value is refused rather than
     * quietly ignored: a caller that meant to pin an identity and mistyped it must not be handed the
     * guessed one and told nothing.
     */
    public static Identity parse(String header) {
        if (header == null || header.isBlank()) return null;
        String value = header.trim().toLowerCase(Locale.ROOT);
        for (Identity identity : values()) if (identity.wire().equals(value)) return identity;
        throw new IllegalArgumentException("Unknown identity '" + header.trim() + "'; expected app or account");
    }
}
