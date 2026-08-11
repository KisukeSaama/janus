package io.janus.gateway;

import java.util.*;

import org.springframework.http.HttpHeaders;

/**
 * Decides which headers cross the boundary in each direction.
 *
 * <p>Outbound, this drops hop-by-hop headers, Janus's own authentication headers, and anything
 * describing the inbound hop — a caller must not be able to shape what the upstream sees about who
 * is calling. Inbound, it drops the upstream's authentication and session material so a provider
 * cannot set a cookie or challenge in the caller's context, plus the length and framing headers,
 * which no longer describe the body once Janus has re-encoded it.
 *
 * <p>The {@code X-Janus-} namespace is reserved in both directions, whatever the header. It is how
 * Janus states what it decided — what the store did, what allowance is left, how many attempts it
 * took — and a caller reads those to behave correctly. Only some of them are written unconditionally:
 * the rate-limit trio is written only when a quota exists, and the attempt count only after a retry.
 * Left unfiltered, an upstream could supply the others itself and tell a caller it had allowance it
 * does not have, or that an answer was served from cache when it was not. The same rule outbound:
 * a caller must not be able to make an upstream believe the gateway said something.
 */
final class HeaderPolicy {

    /** Reserved in both directions: only Janus itself speaks in this namespace. */
    private static final String JANUS_PREFIX = "x-janus-";

    private static final Set<String> BLOCKED_REQUEST = Set.of(
            "host",
            "authorization",
            "proxy-authorization",
            "cookie",
            "connection",
            "keep-alive",
            "proxy-connection",
            "content-length",
            "transfer-encoding",
            "te",
            "trailer",
            "upgrade",
            "expect",
            "accept-encoding",
            "via",
            "forwarded",
            "x-forwarded-for",
            "x-forwarded-host",
            "x-forwarded-proto",
            "x-forwarded-port",
            "x-real-ip",
            "x-correlation-id",
            // A browser sends this on every cross-origin call. It describes the hop into Janus and
            // says nothing true about the hop out of it.
            "origin");

    private static final Set<String> BLOCKED_RESPONSE = Set.of(
            "set-cookie",
            "set-cookie2",
            "authorization",
            "proxy-authorization",
            "www-authenticate",
            "proxy-authenticate",
            "connection",
            "keep-alive",
            "proxy-connection",
            "transfer-encoding",
            "trailer",
            "te",
            "upgrade",
            "content-length",
            // The upstream's own CORS answer. Janus states its own, and a browser that receives two
            // Access-Control-Allow-Origin headers rejects the whole response — so returning an API's
            // headers alongside Janus's would break every browser call to precisely the modern
            // providers that set them.
            "access-control-allow-origin",
            "access-control-allow-credentials",
            "access-control-allow-methods",
            "access-control-allow-headers",
            "access-control-expose-headers",
            "access-control-max-age");

    private HeaderPolicy() {}

    static boolean isRequestHeaderForwarded(String name) {
        String lowercase = name.toLowerCase(Locale.ROOT);
        return !lowercase.startsWith(JANUS_PREFIX) && !BLOCKED_REQUEST.contains(lowercase);
    }

    static boolean isResponseHeaderReturned(String name) {
        String lowercase = name.toLowerCase(Locale.ROOT);
        return !lowercase.startsWith(JANUS_PREFIX) && !BLOCKED_RESPONSE.contains(lowercase);
    }

    static HttpHeaders filterResponseHeaders(HttpHeaders upstream) {
        var headers = new HttpHeaders();
        upstream.forEach((name, values) -> {
            if (isResponseHeaderReturned(name)) headers.put(name, values);
        });
        return headers;
    }
}
