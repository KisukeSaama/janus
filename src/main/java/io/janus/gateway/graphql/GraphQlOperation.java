package io.janus.gateway.graphql;

import java.util.*;

/**
 * One operation, as much of it as the gateway needs to decide anything: what kind it is, which root
 * fields it reaches, and how large it is. Never the variables, and never the document itself, so
 * nothing a caller put in a string literal reaches the journal.
 *
 * @param name       the operation's own name, or null for an anonymous one
 * @param rootFields the fields selected at the top of the operation, fragments expanded, aliases
 *                   resolved to the field they stand for; {@code __typename} is left out, because it
 *                   reaches nothing
 * @param depth      how deeply selection sets nest, one for a selection of scalars
 * @param aliases    how many fields were selected under a name of the caller's choosing
 */
public record GraphQlOperation(OperationType type, String name, Set<String> rootFields, int depth, int aliases) {

    public GraphQlOperation {
        // Sorted, so the same operation reads the same way in the journal and in a memory key
        // whichever order its fields were written in.
        rootFields = Collections.unmodifiableSortedSet(new TreeSet<>(rootFields));
    }

    /** For the journal: {@code query GetViewer [viewer]}. */
    public String describe() {
        var text = new StringBuilder(type.wire());
        if (name != null) text.append(' ').append(name);
        return text.append(" [")
                .append(String.join(",", rootFields))
                .append(']')
                .toString();
    }
}
