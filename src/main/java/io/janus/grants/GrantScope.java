package io.janus.grants;

import java.util.*;

/**
 * How much of a destination's surface one grant admits.
 *
 * <p>Empty by default, and empty means the whole of it, which is what a grant has always meant and
 * what every existing one goes on meaning. Registering an API is a statement about a destination
 * rather than about a subset of its paths, and the API's own authorisation is what decides which of
 * them the credential Janus presents may touch. Copying that answer here would only produce a
 * staler one.
 *
 * <p>This exists for the credential that answer cannot be had from. A self-hosted deployment is full
 * of them: a media server's token is its administrator, a home automation hub issues one key for the
 * whole house, a NAS has no scopes to grant. There the upstream cannot say "this service may only
 * read", so without something here nobody can, and a compromised dashboard holds everything the key
 * holds. What this narrows is not what the API permits. It is what Janus is willing to ask it for
 * on this application's behalf.
 *
 * <p>Deliberately the smallest thing that does that. One prefix, an optional set of methods, no
 * wildcards, no ordering, no precedence: a rule nobody has to work out the meaning of. A grant that
 * needs two unrelated prefixes is two grants, or none.
 *
 * @param pathPrefix the path under which this grant admits calls, or {@code null} for all of them
 * @param methods    the methods it admits, or empty for all of them
 */
public record GrantScope(String pathPrefix, Set<String> methods) {

    /** The whole destination, which is what a grant with nothing stated has always meant. */
    public static final GrantScope EVERYTHING = new GrantScope(null, Set.of());

    private static final int MAX_PREFIX = 512;

    /**
     * The methods the gateway forwards at all, in the order they are read in: naming any other one is
     * a mistake worth reporting, and a stored list reads the same whichever order it was written in.
     */
    private static final List<String> KNOWN = List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE");

    public GrantScope {
        pathPrefix = normalise(pathPrefix);
        methods = methods == null ? Set.of() : Set.copyOf(methods);
    }

    /**
     * Reads back what was stored: a prefix, and methods as the comma-separated list a column holds.
     *
     * <p>Whatever cannot be read is read as admitting everything. A grant is not the place to fail
     * closed on a value nobody can correct from where the request is being refused, and there is no
     * value this can be given that widens it past what the grant itself already admits.
     */
    public static GrantScope of(String pathPrefix, String methods) {
        if (isBlank(pathPrefix) && isBlank(methods)) return EVERYTHING;
        var named = new LinkedHashSet<String>();
        if (!isBlank(methods))
            for (String method : methods.split(","))
                if (!method.isBlank()) named.add(method.trim().toUpperCase(Locale.ROOT));
        return new GrantScope(pathPrefix, named);
    }

    /** Whether anything at all is narrowed, which is what the console shows and the journal records. */
    public boolean narrows() {
        return pathPrefix != null || !methods.isEmpty();
    }

    /**
     * Whether a request path falls under the prefix.
     *
     * <p>Compared segment-wise, not as text: {@code /v1/users} must not be admitted by a grant that
     * names {@code /v1/user}, which is exactly the mistake a {@code startsWith} makes. The path
     * arrives decoded and already refused if it carried an empty or dot segment (see
     * {@code RequestUriGuard}), so what is compared here is the path that will actually be sent.
     */
    public boolean admitsPath(String decodedPath) {
        if (pathPrefix == null) return true;
        if (decodedPath == null) return false;
        String path = decodedPath.startsWith("/") ? decodedPath : "/" + decodedPath;
        return path.equals(pathPrefix)
                || path.startsWith(pathPrefix.endsWith("/") ? pathPrefix : pathPrefix + "/");
    }

    public boolean admitsMethod(String method) {
        return methods.isEmpty() || methods.contains(method.toUpperCase(Locale.ROOT));
    }

    /** The prefix as a column holds it. */
    public String storedPrefix() {
        return pathPrefix;
    }

    /** The methods, always in the order above, so a stored value and an API response agree. */
    public List<String> orderedMethods() {
        return KNOWN.stream().filter(methods::contains).toList();
    }

    /** The methods as a column holds them: comma separated. */
    public String storedMethods() {
        return methods.isEmpty() ? null : String.join(",", orderedMethods());
    }

    /**
     * Refuses a prefix that could be read differently by two layers, rather than storing one and
     * discovering at the door that it never matches anything.
     */
    private static String normalise(String value) {
        if (isBlank(value)) return null;
        String prefix = value.trim();
        if (!prefix.startsWith("/")) prefix = "/" + prefix;
        if (prefix.length() > MAX_PREFIX)
            throw new IllegalArgumentException("Path prefix is longer than " + MAX_PREFIX + " characters");
        if (prefix.contains("?") || prefix.contains("#"))
            throw new IllegalArgumentException("Path prefix is a path: it carries no query and no fragment");
        if (prefix.contains("//") || prefix.contains(".."))
            throw new IllegalArgumentException("Path prefix must not contain empty or traversal segments");
        // A trailing slash would make the prefix and the resource it names two different strings, and
        // "/v1/albums/" admitting "/v1/albums" is not a distinction anybody meant to draw.
        while (prefix.length() > 1 && prefix.endsWith("/")) prefix = prefix.substring(0, prefix.length() - 1);
        // "/" is every path, which is the same as having said nothing.
        return prefix.equals("/") ? null : prefix;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Refuses a method the gateway does not forward, so a typo is reported where it is written. */
    public void validate() {
        for (String method : methods)
            if (!KNOWN.contains(method))
                throw new IllegalArgumentException("'" + method + "' is not a method the gateway forwards");
    }
}
