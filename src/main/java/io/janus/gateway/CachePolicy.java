package io.janus.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;

/**
 * The HTTP caching rules, kept apart from the store that applies them.
 *
 * <p>Janus is a shared cache serving machine callers, so it follows RFC 9111 with one deliberate
 * narrowing: an entry is addressed by the credential the request would have been made with, never
 * by the calling application. Two applications holding a grant on the same credential are, as far
 * as the upstream is concerned, the same client, and an application without that grant is refused
 * before the store is ever consulted. Anything marked {@code private}, anything carrying a cookie,
 * and anything with {@code Vary: *} is not stored at all.
 */
final class CachePolicy {
    /**
     * Key separator. A gateway path is rejected outright if it decodes to a control character, so a
     * NUL can only ever be a separator and no key is ambiguous.
     */
    static final char SEPARATOR = '\0';

    /** Statuses worth reusing without the upstream having said so, when a provider sets a default TTL. */
    private static final Set<Integer> HEURISTICALLY_CACHEABLE = Set.of(200, 203, 204, 301, 308, 404, 410);
    /** Statuses reusable when the upstream states a freshness lifetime explicitly. */
    private static final Set<Integer> EXPLICITLY_CACHEABLE = Set.of(200, 203, 204, 300, 301, 302, 307, 308, 404, 410);

    private CachePolicy() {}

    /**
     * Addresses one stored response. The provider, credential, and decoded path stay readable so
     * entries can be dropped by resource when one is written to; everything else that can
     * legitimately change a body — the original encoding, the query, the negotiated representation
     * — is folded into a digest.
     */
    static String key(UUID providerId, UUID credentialId, String method, GatewayPath route, HttpHeaders request) {
        String variant = String.join(
                "|",
                Objects.requireNonNullElse(route.rawPath(), ""),
                Objects.requireNonNullElse(route.rawQuery(), ""),
                Objects.requireNonNullElse(request.getFirst(HttpHeaders.ACCEPT), ""),
                Objects.requireNonNullElse(request.getFirst(HttpHeaders.ACCEPT_LANGUAGE), ""));
        return resourcePrefix(providerId, credentialId)
                + route.decodedPath()
                + SEPARATOR
                + method
                + SEPARATOR
                + digest(variant);
    }

    /** Prefix shared by every entry a given credential holds at a given provider. */
    static String resourcePrefix(UUID providerId, UUID credentialId) {
        return providerId + String.valueOf(SEPARATOR) + credentialId + SEPARATOR;
    }

    static String providerPrefix(UUID providerId) {
        return providerId + String.valueOf(SEPARATOR);
    }

    static String credentialToken(UUID credentialId) {
        return SEPARATOR + credentialId.toString() + SEPARATOR;
    }

    /**
     * True when a key names the resource at {@code decodedPath} or something beneath it. A write to
     * a collection invalidates the collection and its members, which is what an API caller means
     * when they create, replace, or delete something.
     */
    static boolean covers(String key, String resourcePrefix, String decodedPath) {
        if (!key.startsWith(resourcePrefix)) return false;
        String rest = key.substring(resourcePrefix.length());
        int end = rest.indexOf(SEPARATOR);
        String path = end < 0 ? rest : rest.substring(0, end);
        String branch = decodedPath.endsWith("/") ? decodedPath : decodedPath + "/";
        return path.equals(decodedPath) || path.startsWith(branch);
    }

    /** True when the caller asked that this exchange leave no trace in the store. */
    static boolean callerRefusesStorage(HttpHeaders request) {
        return directives(request.getFirst(HttpHeaders.CACHE_CONTROL)).containsKey("no-store");
    }

    /**
     * True when the caller wants this answer fetched rather than reused. The response may still be
     * stored afterwards, unless {@code no-store} also said otherwise.
     */
    static boolean callerRefusesReuse(HttpHeaders request) {
        var directives = directives(request.getFirst(HttpHeaders.CACHE_CONTROL));
        if (directives.containsKey("no-cache") || directives.containsKey("no-store")) return true;
        Long maxAge = number(directives.get("max-age"));
        return maxAge != null && maxAge == 0;
    }

    /**
     * A caller running its own conditional or partial request owns that exchange end to end: mixing
     * a stored validator into it would answer a question the caller did not ask.
     */
    static boolean callerIsConditional(HttpHeaders request) {
        return request.containsHeader(HttpHeaders.IF_NONE_MATCH)
                || request.containsHeader(HttpHeaders.IF_MODIFIED_SINCE)
                || request.containsHeader(HttpHeaders.IF_MATCH)
                || request.containsHeader(HttpHeaders.IF_UNMODIFIED_SINCE)
                || request.containsHeader(HttpHeaders.RANGE);
    }

    /**
     * @param storable     whether the response may be stored at all
     * @param freshSeconds freshness lifetime; zero means storable but revalidated on every use
     * @param staleSeconds how long after expiry it may still answer while the upstream is failing
     */
    record Storability(boolean storable, long freshSeconds, long staleSeconds) {
        static final Storability NO = new Storability(false, 0, 0);
    }

    static Storability evaluate(
            HttpStatusCode status, HttpHeaders upstream, int providerTtlSeconds, long defaultStaleIfErrorSeconds) {
        var directives = directives(upstream.getFirst(HttpHeaders.CACHE_CONTROL));
        if (directives.containsKey("no-store") || directives.containsKey("private")) return Storability.NO;
        // Set-Cookie is stripped before anything is returned, but its presence still says the
        // response was shaped for one session rather than for one credential.
        if (upstream.containsHeader(HttpHeaders.SET_COOKIE)) return Storability.NO;
        for (String vary : varyNames(upstream)) if (vary.equals("*")) return Storability.NO;

        long stale = directives.containsKey("must-revalidate") || directives.containsKey("proxy-revalidate")
                ? 0
                : Objects.requireNonNullElse(number(directives.get("stale-if-error")), defaultStaleIfErrorSeconds);
        boolean explicitlyCacheable = EXPLICITLY_CACHEABLE.contains(status.value());

        if (directives.containsKey("no-cache"))
            return explicitlyCacheable ? new Storability(true, 0, stale) : Storability.NO;

        Long lifetime = number(directives.get("s-maxage"));
        if (lifetime == null) lifetime = number(directives.get("max-age"));
        if (lifetime == null) lifetime = expiresInSeconds(upstream);
        if (lifetime != null)
            return lifetime >= 0 && explicitlyCacheable ? new Storability(true, lifetime, stale) : Storability.NO;

        return providerTtlSeconds > 0 && HEURISTICALLY_CACHEABLE.contains(status.value())
                ? new Storability(true, providerTtlSeconds, stale)
                : Storability.NO;
    }

    /**
     * How old the upstream already considered the response to be. Counting it means a response that
     * spent four of its five allowed minutes in another cache is not reused here for five more.
     */
    static long upstreamAgeSeconds(HttpHeaders upstream) {
        Long age = number(upstream.getFirst(HttpHeaders.AGE));
        return age == null || age < 0 ? 0 : age;
    }

    /** Header names a response varies by, lowercased. */
    static List<String> varyNames(HttpHeaders upstream) {
        var names = new ArrayList<String>();
        for (String value : upstream.getOrEmpty(HttpHeaders.VARY))
            for (String name : value.split(","))
                if (!name.isBlank()) names.add(name.trim().toLowerCase(Locale.ROOT));
        return names;
    }

    /** Parses {@code Retry-After} in both of its forms into seconds, or {@code null}. */
    static Long retryAfterSeconds(HttpHeaders headers) {
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) return null;
        Long seconds = number(value);
        if (seconds != null) return Math.max(0, seconds);
        try {
            long until = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(value.trim()))
                    .getEpochSecond();
            return Math.max(0, until - Instant.now().getEpochSecond());
        } catch (DateTimeException ex) {
            return null;
        }
    }

    private static Long expiresInSeconds(HttpHeaders upstream) {
        if (!upstream.containsHeader(HttpHeaders.EXPIRES)) return null;
        try {
            var expires = upstream.getFirstZonedDateTime(HttpHeaders.EXPIRES);
            if (expires == null) return 0L;
            var date =
                    upstream.containsHeader(HttpHeaders.DATE) ? upstream.getFirstZonedDateTime(HttpHeaders.DATE) : null;
            long from = date == null ? Instant.now().getEpochSecond() : date.toEpochSecond();
            return Math.max(0, expires.toEpochSecond() - from);
        } catch (IllegalArgumentException | DateTimeException ex) {
            // An unparseable Expires means "already expired": a decision the upstream made, not a failure.
            return 0L;
        }
    }

    /** Splits a Cache-Control field into directives; a valueless directive maps to an empty string. */
    private static Map<String, String> directives(String header) {
        if (header == null || header.isBlank()) return Map.of();
        var directives = new HashMap<String, String>();
        for (String token : header.split(",")) {
            String directive = token.trim();
            if (directive.isEmpty()) continue;
            int equals = directive.indexOf('=');
            String name = (equals < 0 ? directive : directive.substring(0, equals))
                    .trim()
                    .toLowerCase(Locale.ROOT);
            String value =
                    equals < 0 ? "" : directive.substring(equals + 1).trim().replace("\"", "");
            directives.put(name, value);
        }
        return directives;
    }

    private static Long number(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String digest(String value) {
        try {
            var sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }
}
