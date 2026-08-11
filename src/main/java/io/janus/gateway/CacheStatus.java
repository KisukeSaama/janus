package io.janus.gateway;

/** What the gateway did with its store for one request, reported as {@code X-Janus-Cache}. */
public enum CacheStatus {
    /** Answered from a fresh stored response. No credential was read and nothing left the process. */
    HIT,
    /** Nothing usable was stored; the upstream answered. */
    MISS,
    /** The stored response was stale and the upstream confirmed it with 304. */
    REVALIDATED,
    /** The upstream was failing or in cooldown, and a stale response was served rather than an error. */
    STALE,
    /** An identical request was already in flight; this one waited for it instead of duplicating it. */
    COALESCED,
    /** Caching did not apply: not a safe method, disabled, or the caller asked for a fresh copy. */
    BYPASS
}
