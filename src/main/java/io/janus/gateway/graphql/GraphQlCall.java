package io.janus.gateway.graphql;

import java.util.List;
import java.util.stream.Collectors;

/**
 * What a request to a GraphQL endpoint asks for, read before anything is forwarded.
 *
 * <p>Usually one operation. A batch is several sent together, and is judged by the most consequential
 * of them: it only reads if every one of them reads, and it writes if any one of them does, because
 * the upstream executes them all.
 *
 * <p>Empty is the one request that could not be read at all: a persisted query whose document Janus
 * has never seen, sent where nothing required reading it. It is forwarded, and treated as the
 * write it might be: never stored, never retried, never replayed.
 *
 * @param batch whether the operations arrived as a JSON array rather than as one object
 */
public record GraphQlCall(List<GraphQlOperation> operations, boolean batch) {

    /** Longest summary the journal receives; its detail column has room for a sentence, not a query plan. */
    private static final int DESCRIPTION_LIMIT = 300;

    public GraphQlCall {
        operations = List.copyOf(operations);
    }

    public static GraphQlCall of(GraphQlOperation operation) {
        return new GraphQlCall(List.of(operation), false);
    }

    /** A request whose document Janus could not read, and did not need to. */
    public static GraphQlCall unread() {
        return new GraphQlCall(List.of(), false);
    }

    public boolean unreadable() {
        return operations.isEmpty();
    }

    /** Whether every operation only reads, which is what licenses reuse and a second attempt. */
    public boolean readsOnly() {
        return !operations.isEmpty() && operations.stream().allMatch(op -> op.type() == OperationType.QUERY);
    }

    /** Whether anything here may change state upstream. An unread request is assumed to. */
    public boolean writes() {
        return operations.isEmpty() || operations.stream().anyMatch(op -> op.type() == OperationType.MUTATION);
    }

    public boolean subscribes() {
        return operations.stream().anyMatch(op -> op.type() == OperationType.SUBSCRIPTION);
    }

    /**
     * The kind that decides how the call is handled, for a tag with three values rather than one per
     * operation: a batch holding a mutation is a mutation, whatever else it holds.
     */
    public OperationType governingType() {
        if (writes()) return OperationType.MUTATION;
        if (subscribes()) return OperationType.SUBSCRIPTION;
        return OperationType.QUERY;
    }

    /** What the journal says about the call. */
    public String describe() {
        if (operations.isEmpty()) return "graphql, persisted query not read";
        String text =
                "graphql " + operations.stream().map(GraphQlOperation::describe).collect(Collectors.joining("; "));
        return text.length() <= DESCRIPTION_LIMIT ? text : text.substring(0, DESCRIPTION_LIMIT - 1) + "…";
    }

    /**
     * The shape of the call, for remembering which identity it answers to. Two calls selecting the
     * same root fields with the same kind of operation reach the same data, whatever the caller named
     * them; the name is chosen by the caller and says nothing about the endpoint.
     */
    public String shape() {
        return operations.stream()
                .map(op -> op.type().wire() + ":" + String.join(",", op.rootFields()))
                .collect(Collectors.joining(";"));
    }
}
