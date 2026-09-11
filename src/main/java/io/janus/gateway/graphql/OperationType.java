package io.janus.gateway.graphql;

import java.util.Locale;

import graphql.language.OperationDefinition;

/**
 * The three kinds of GraphQL operation, which is the distinction a GraphQL API is actually governed
 * by: a query reads, a mutation writes, a subscription stays open. On the wire all three are the same
 * {@code POST} to the same path.
 */
public enum OperationType {
    QUERY,
    MUTATION,
    SUBSCRIPTION;

    private final String wire = name().toLowerCase(Locale.ROOT);

    /** The keyword a document spells it with, which is also how the journal writes it. */
    public String wire() {
        return wire;
    }

    static OperationType of(OperationDefinition.Operation operation) {
        return switch (operation) {
            case QUERY -> QUERY;
            case MUTATION -> MUTATION;
            case SUBSCRIPTION -> SUBSCRIPTION;
        };
    }

    /** Reads a stored or submitted value, refusing anything that is not one of the three. */
    public static OperationType parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("'" + value.trim() + "' is not a GraphQL operation type");
        }
    }
}
