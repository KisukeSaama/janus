package io.janus.gateway;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.web.util.UriComponentsBuilder;

import io.janus.shared.ErrorCode;

/**
 * The caller-supplied portion of a gateway URL, in both the form Janus reasons about and the form
 * sent upstream.
 *
 * <p>The request is forwarded with its original encoding, and everything Janus decides for itself —
 * which stored response a write invalidates, what the journal records — is decided on the decoded
 * form. Reasoning on the raw path instead would let {@code /v1/%61dmin} and {@code /v1/admin} name
 * two different resources while reaching the same one; decoding before forwarding would corrupt any
 * path where the encoding is meaningful. Encoded separators are rejected outright, because with them
 * the two forms disagree about where segments begin.
 *
 * @param rawPath     path exactly as received, still percent-encoded
 * @param decodedPath path after percent-decoding, the form Janus keys and audits on
 * @param rawQuery    query string as received, or {@code null}
 */
public record GatewayPath(String rawPath, String decodedPath, String rawQuery) {

    /**
     * Characters that must appear percent-encoded in a URI. Rejecting them in the raw form keeps the
     * request unambiguous; {@code {} } is included because it would otherwise be read as a URI template
     * variable when the target is assembled.
     */
    private static final String FORBIDDEN_RAW_LITERALS = " \"<>{}|\\^`";

    public static GatewayPath parse(String requestUri, String slug, String queryString) {
        return parseWithPrefix(requestUri, "/gateway/" + slug, queryString);
    }

    private static GatewayPath parseWithPrefix(String requestUri, String prefix, String queryString) {
        if (!requestUri.startsWith(prefix))
            throw new GatewayController.Denied(HttpStatus.BAD_REQUEST, ErrorCode.PATH_INVALID, "Unsafe gateway path");

        String rawPath = requestUri.substring(prefix.length());
        if (rawPath.isEmpty()) rawPath = "/";
        if (!rawPath.startsWith("/") || rawPath.contains("//"))
            throw new GatewayController.Denied(HttpStatus.BAD_REQUEST, ErrorCode.PATH_INVALID, "Unsafe gateway path");
        rejectUnsafeRawCharacters(rawPath);

        // A space or a brace is legitimate once decoded — "/v1/customers/john%20doe" is an ordinary
        // request — so the decoded form is only checked for what is dangerous in any form.
        String decodedPath = percentDecode(rawPath);
        rejectUnsafeDecodedCharacters(decodedPath);
        if (decodedPath.contains("//") || hasTraversalSegment(decodedPath))
            throw new GatewayController.Denied(HttpStatus.BAD_REQUEST, ErrorCode.PATH_INVALID, "Unsafe gateway path");

        if (queryString != null) rejectUnsafeRawCharacters(queryString);
        return new GatewayPath(rawPath, decodedPath, queryString);
    }

    /** Joins this path onto a validated provider base URL, preserving the original encoding. */
    public URI toTargetUri(String baseUrl) {
        var builder = UriComponentsBuilder.fromUriString(baseUrl);
        String basePath = builder.build().getPath();
        // The console quite naturally accepts both `https://api.example.com/v1` and the same base
        // address with a trailing slash. Joining the latter verbatim used to produce `/v1//items`,
        // which changes the resource on strict APIs (and is rejected by some of them outright).
        // Keep exactly one slash at the boundary; the caller path itself always starts with one.
        String normalizedBasePath = basePath == null ? "" : basePath.replaceFirst("/+$", "");
        builder.replacePath(normalizedBasePath + rawPath);
        if (rawQuery != null) builder.query(rawQuery);
        try {
            return builder.build(true).toUri();
        } catch (IllegalArgumentException ex) {
            throw new GatewayController.Denied(HttpStatus.BAD_REQUEST, ErrorCode.PATH_INVALID, "Unsafe gateway path");
        }
    }

    private static void rejectUnsafeRawCharacters(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f || FORBIDDEN_RAW_LITERALS.indexOf(c) >= 0)
                throw new GatewayController.Denied(
                        HttpStatus.BAD_REQUEST, ErrorCode.PATH_INVALID, "Unsafe gateway path");
        }
    }

    /** Control characters survive no legitimate use and are how header and log injection is attempted. */
    private static void rejectUnsafeDecodedCharacters(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '\\')
                throw new GatewayController.Denied(
                        HttpStatus.BAD_REQUEST, ErrorCode.PATH_INVALID, "Unsafe gateway path");
        }
    }

    private static boolean hasTraversalSegment(String path) {
        for (String segment : path.split("/", -1)) if (segment.equals("..") || segment.equals(".")) return true;
        return false;
    }

    /**
     * Decodes percent-escapes as UTF-8. Unlike {@code URLDecoder}, {@code +} is left alone — it is a
     * literal plus in a path — and an encoded separator is refused rather than silently accepted.
     */
    private static String percentDecode(String value) {
        if (value.indexOf('%') < 0) return value;
        var decoded = new StringBuilder(value.length());
        var pending = new ByteArrayOutputStream(4);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '%') {
                flush(pending, decoded);
                decoded.append(c);
                continue;
            }
            if (i + 2 >= value.length())
                throw new GatewayController.Denied(
                        HttpStatus.BAD_REQUEST, ErrorCode.PATH_INVALID, "Unsafe gateway path");
            int high = Character.digit(value.charAt(i + 1), 16);
            int low = Character.digit(value.charAt(i + 2), 16);
            if (high < 0 || low < 0)
                throw new GatewayController.Denied(
                        HttpStatus.BAD_REQUEST, ErrorCode.PATH_INVALID, "Unsafe gateway path");
            int octet = (high << 4) + low;
            if (octet == '/' || octet == '\\' || octet == 0)
                throw new GatewayController.Denied(
                        HttpStatus.BAD_REQUEST, ErrorCode.PATH_INVALID, "Encoded path separators are not accepted");
            pending.write(octet);
            i += 2;
        }
        flush(pending, decoded);
        return decoded.toString();
    }

    /** Decodes a run of consecutive escapes as one UTF-8 sequence, so multi-byte characters survive. */
    private static void flush(ByteArrayOutputStream pending, StringBuilder target) {
        if (pending.size() == 0) return;
        target.append(pending.toString(StandardCharsets.UTF_8));
        pending.reset();
    }
}
