package io.janus.gateway.graphql;

/**
 * Where a destination's GraphQL endpoint is, and whether a request path is it.
 *
 * <p>Compared generously on purpose. Express, and much of what GraphQL servers run on, routes without
 * regard to case or a trailing slash, so {@code /GraphQL/} reaches the same endpoint as
 * {@code /graphql}. A comparison stricter than the server's would be one a caller could step around:
 * the call would not be read as GraphQL here, and would still execute as GraphQL there.
 */
public final class GraphQlEndpoint {
    static final int MAX_PATH = 200;

    private GraphQlEndpoint() {}

    /** Whether a decoded request path is the endpoint declared as {@code endpointPath}. */
    public static boolean matches(String endpointPath, String decodedPath) {
        if (endpointPath == null || decodedPath == null) return false;
        return trimmed(endpointPath).equalsIgnoreCase(trimmed(decodedPath));
    }

    /**
     * The stored form of a declared path, or null when none was given. Refuses a path that two layers
     * could read differently rather than storing one no request would ever match.
     */
    public static String normalise(String value) {
        if (value == null || value.isBlank()) return null;
        String path = value.trim();
        if (!path.startsWith("/")) path = "/" + path;
        if (path.length() > MAX_PATH)
            throw new IllegalArgumentException("GraphQL endpoint path is longer than " + MAX_PATH + " characters");
        if (path.contains("?") || path.contains("#"))
            throw new IllegalArgumentException("GraphQL endpoint path is a path: it carries no query and no fragment");
        if (path.contains("//") || path.contains(".."))
            throw new IllegalArgumentException("GraphQL endpoint path must not contain empty or traversal segments");
        for (int i = 0; i < path.length(); i++)
            if (path.charAt(i) < 0x20 || path.charAt(i) == 0x7f || path.charAt(i) == '\\')
                throw new IllegalArgumentException("GraphQL endpoint path contains a character no path may carry");
        return trimmed(path);
    }

    private static String trimmed(String path) {
        String result = path;
        while (result.length() > 1 && result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }
}
