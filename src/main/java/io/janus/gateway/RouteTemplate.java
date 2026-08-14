package io.janus.gateway;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A path reduced to the shape it shares with every other call to the same endpoint.
 *
 * <p>This is what makes {@link IdentityMemory} worth having. What Janus learns is "this endpoint
 * wants the account's token", and an endpoint is not a path: {@code /playlists/3cEYpjA9oz9GiPac} and
 * {@code /playlists/6Qs4SXO9dwPj5GKvFOSXuI} are one endpoint reached twice. Remembering them
 * separately would mean every call is a path never seen before, every call pays the replay, and
 * nothing is ever learned — the memory would fill up while remaining useless.
 *
 * <p>The reduction is a heuristic and does not need to be better than one. Merging two endpoints that
 * differ costs a replay the first time the second one is reached; splitting one endpoint in two costs
 * a replay per variant. Neither is wrong, only slower, which is the licence to keep the rule short.
 */
final class RouteTemplate {

    /** How many segments are kept. Beyond this the tail says nothing a template needs. */
    private static final int MAX_SEGMENTS = 8;

    private static final Pattern VERSION = Pattern.compile("v\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern HAS_DIGIT = Pattern.compile(".*\\d.*");

    private RouteTemplate() {}

    /**
     * The template for a decoded gateway path.
     *
     * <p>A segment is replaced when it carries a digit or runs longer than sixteen characters, which
     * between them cover numeric ids, UUIDs, and the base62 identifiers most modern APIs issue.
     * {@code v2} is kept: a version is part of an endpoint's name, not a value it varies by, and it is
     * the one common segment the digit rule would otherwise swallow.
     */
    static String of(String decodedPath) {
        if (decodedPath == null || decodedPath.isEmpty() || decodedPath.equals("/")) return "/";

        var template = new StringBuilder(decodedPath.length());
        int segments = 0;
        for (String segment : decodedPath.split("/", -1)) {
            if (segment.isEmpty()) continue;
            if (++segments > MAX_SEGMENTS) {
                template.append("/…");
                break;
            }
            template.append('/').append(identifier(segment) ? "*" : segment.toLowerCase(Locale.ROOT));
        }
        return template.isEmpty() ? "/" : template.toString();
    }

    private static boolean identifier(String segment) {
        if (VERSION.matcher(segment).matches()) return false;
        return segment.length() > 16 || HAS_DIGIT.matcher(segment).matches();
    }
}
