package io.janus.gateway;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.springframework.http.*;

/**
 * Last line of defence against an upstream that echoes the credential it was given — some APIs
 * reflect the request in an error body, and some reflect it in a header. This is a safety net, not
 * the boundary itself: the boundary is that the caller never receives the credential in the first
 * place.
 *
 * <p>Only textual, unencoded payloads are examined. Substituting bytes inside a compressed or binary
 * response would corrupt it without ever matching the plaintext secret. Response headers, on the
 * other hand, are always text and are always examined — an API that echoes what it was sent into a
 * debug header hands the caller the very value the gateway exists to withhold.
 *
 * <p>A value shorter than {@link #MIN_LENGTH} is left alone. No credential is that short, and a
 * three-character string matched against every header and body would corrupt far more than it
 * protected.
 */
final class SecretRedactor {
    static final String PLACEHOLDER = "[REDACTED]";

    /** Below this, a match says nothing: it is a coincidence, not a leak. */
    private static final int MIN_LENGTH = 8;

    private SecretRedactor() {}

    /**
     * @param secrets every value that must not come back. Usually one; two when the credential was
     *     exchanged for a token, since both the token that was sent and the client secret it was
     *     obtained with can be echoed by an upstream that is unhappy about either.
     */
    static byte[] scrub(byte[] body, HttpHeaders upstreamHeaders, String... secrets) {
        if (body == null || body.length == 0 || secrets == null) return body;
        if (!isInspectable(upstreamHeaders)) return body;

        String text = new String(body, StandardCharsets.UTF_8);
        String scrubbed = replaceAll(text, secrets);
        return scrubbed.equals(text) ? body : scrubbed.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The same substitution over every header value that is about to be returned. Header names are
     * left as they are: a name cannot carry a secret, and rewriting one would only lose the header.
     */
    static HttpHeaders scrubHeaders(HttpHeaders headers, String... secrets) {
        if (secrets == null || headers.isEmpty()) return headers;
        var scrubbed = new HttpHeaders();
        headers.forEach((name, values) -> scrubbed.put(
                name,
                values.stream()
                        .map(value -> value == null ? null : replaceAll(value, secrets))
                        .toList()));
        return scrubbed;
    }

    private static String replaceAll(String text, String... secrets) {
        String scrubbed = text;
        for (String secret : secrets) {
            if (secret == null || secret.length() < MIN_LENGTH) continue;
            String base64Secret = Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8));
            scrubbed = scrubbed.replace(secret, PLACEHOLDER).replace(base64Secret, PLACEHOLDER);
        }
        return scrubbed;
    }

    private static boolean isInspectable(HttpHeaders headers) {
        MediaType contentType = headers.getContentType();
        if (contentType == null) return false;
        boolean textual = "text".equals(contentType.getType())
                || contentType.isCompatibleWith(MediaType.APPLICATION_JSON)
                || contentType.getSubtype().endsWith("+json");
        if (!textual) return false;
        String encoding = headers.getFirst(HttpHeaders.CONTENT_ENCODING);
        return encoding == null || "identity".equalsIgnoreCase(encoding.trim());
    }
}
