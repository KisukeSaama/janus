package io.janus.credentials;

import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The string an API wants signed, written as a template.
 *
 * <p>Every signed-request API agrees that a secret should sign the request and disagrees about what
 * "the request" means. Coinbase signs {@code timestamp + method + path + body}; Binance signs the
 * query string alone. Neither follows from anything, so neither is guessed: an administrator records
 * the recipe once, beside the destination it belongs to, and Janus follows it literally.
 *
 * <p>The placeholders below are the whole vocabulary. Anything outside them is copied through as
 * written, which is what lets a recipe carry the separators an API expects — a template is a format,
 * not a list of parts.
 *
 * <p>What is deliberately absent is any placeholder that would reach the secret. A template is stored
 * in the clear and shown in the console; a recipe that could interpolate the key would turn both of
 * those into places a secret leaks. The secret's only role is to be the key of the MAC.
 */
public record SignatureTemplate(String pattern) {
    /** Milliseconds since the epoch, and the reason the unit is not a separate setting. */
    public static final String TIMESTAMP_MILLIS = "{timestamp_ms}";

    /** What Binance and the APIs shaped like it sign: the query string, timestamp included. */
    public static final String QUERY_STRING = "{query}";

    /** What Coinbase and the APIs shaped like it sign. */
    public static final String TIMESTAMP_METHOD_PATH_BODY = "{timestamp}{method}{path}{body}";

    public static final int MAX_LENGTH = 500;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[a-z_]+}");
    private static final List<String> KNOWN =
            List.of("{method}", "{path}", QUERY_STRING, "{body}", "{timestamp}", TIMESTAMP_MILLIS);

    public SignatureTemplate {
        if (pattern == null || pattern.isBlank())
            throw new IllegalArgumentException("A signing recipe is required, such as " + TIMESTAMP_METHOD_PATH_BODY);
        pattern = pattern.trim();
        if (pattern.length() > MAX_LENGTH)
            throw new IllegalArgumentException("A signing recipe may not exceed " + MAX_LENGTH + " characters");

        var unknown = PLACEHOLDER
                .matcher(pattern)
                .results()
                .map(MatchResult::group)
                .filter(found -> !KNOWN.contains(found))
                .findFirst();
        if (unknown.isPresent())
            throw new IllegalArgumentException(
                    "Unknown placeholder " + unknown.get() + " in the signing recipe; " + KNOWN + " are understood");
    }

    /**
     * Whether the recipe counts time in milliseconds. Derived from the template rather than stored
     * separately so the two can never disagree — a timestamp header saying one thing while the signed
     * string says another is a failure no error message would explain.
     */
    public boolean millis() {
        return pattern.contains(TIMESTAMP_MILLIS);
    }

    /** The string to sign, with every placeholder replaced by what this request actually carries. */
    public String expand(Parts parts) {
        var out = new StringBuilder(pattern.length() + 64);
        var matcher = PLACEHOLDER.matcher(pattern);
        while (matcher.find()) matcher.appendReplacement(out, Matcher.quoteReplacement(parts.valueOf(matcher.group())));
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * What a single outbound request offers a template.
     *
     * @param method the HTTP method, uppercase
     * @param path the target path, leading slash included and nothing else — no host, no query
     * @param query the final query string without its {@code ?}, empty when there is none
     * @param body the request body as it will be sent, empty when there is none
     * @param timestamp the instant this request was signed at, in the unit the template asked for
     */
    public record Parts(String method, String path, String query, String body, long timestamp) {
        String valueOf(String placeholder) {
            return switch (placeholder) {
                case "{method}" -> method;
                case "{path}" -> path;
                case QUERY_STRING -> query;
                case "{body}" -> body;
                case "{timestamp}", TIMESTAMP_MILLIS -> Long.toString(timestamp);
                    // Unreachable: the constructor refused anything else before this could be reached.
                default -> throw new IllegalStateException("Unknown placeholder " + placeholder);
            };
        }
    }
}
