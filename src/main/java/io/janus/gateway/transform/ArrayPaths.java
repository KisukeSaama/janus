package io.janus.gateway.transform;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The names a converted document must always present as arrays.
 *
 * <p>XML has no way to say "this is a list", and neither does a form body. An element appearing once
 * is indistinguishable from a list of one, so the only list a converter can infer is one it happens
 * to see repeated — which makes the shape of the JSON depend on the data rather than on the API. A
 * Plex library holding one section returns an object where the same library holding two returns an
 * array, and the client breaks on the day somebody adds a section.
 *
 * <p>Nothing but a schema settles that, and the APIs that answer XML are the ones that publish none.
 * So it is declared per destination instead, in two forms:
 *
 * <ul>
 *   <li>a bare name — {@code Directory} — forces that element wherever it appears
 *   <li>a dotted path — {@code MediaContainer.Directory} — forces it at that one place, counted from
 *       the root element
 * </ul>
 *
 * <p>Declaring nothing leaves the inference in place, which is the right default for an API whose
 * shape a caller already knows.
 */
public final class ArrayPaths {

    /** Nothing declared: a repeated element is a list, a single one is not. */
    public static final ArrayPaths NONE = new ArrayPaths(Set.of(), Set.of());

    /** Long enough for any real declaration, short enough that a pathological one cannot be stored. */
    public static final int MAX_LENGTH = 1000;

    private final Set<String> names;
    private final Set<String> paths;

    private ArrayPaths(Set<String> names, Set<String> paths) {
        this.names = names;
        this.paths = paths;
    }

    /** Reads the stored declaration. Blank entries are dropped rather than refused. */
    public static ArrayPaths parse(String declaration) {
        if (declaration == null || declaration.isBlank()) return NONE;
        var names = new LinkedHashSet<String>();
        var paths = new LinkedHashSet<String>();
        for (String entry : declaration.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            (trimmed.indexOf('.') < 0 ? names : paths).add(trimmed);
        }
        return names.isEmpty() && paths.isEmpty() ? NONE : new ArrayPaths(names, paths);
    }

    /**
     * @param path the element's position, counted from the root and separated by dots
     */
    public boolean forcesArray(String path) {
        if (this == NONE) return false;
        if (paths.contains(path)) return true;
        int lastDot = path.lastIndexOf('.');
        return names.contains(lastDot < 0 ? path : path.substring(lastDot + 1));
    }

    public boolean isEmpty() {
        return names.isEmpty() && paths.isEmpty();
    }
}
