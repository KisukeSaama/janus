package io.janus.gateway.graphql;

import org.springframework.http.HttpStatus;

import io.janus.grants.GrantScope;
import io.janus.providers.Provider;
import io.janus.shared.ErrorCode;

/**
 * Applies a grant's GraphQL ceiling to a call that has been read. The same check for a request and
 * for a WebSocket {@code subscribe}, so a subscription cannot be admitted by a door the request would
 * have been refused at.
 */
public final class GraphQlScope {
    private GraphQlScope() {}

    /**
     * Whether anything depends on reading the document: a grant narrowing GraphQL, or a destination
     * limiting depth or aliases. Where nothing does, a persisted query Janus has never seen is
     * forwarded unread rather than sent back for its document, which is what lets a client using
     * trusted documents work against an API nobody has narrowed.
     */
    public static boolean enforcing(Provider provider, GrantScope scope) {
        return scope.narrowsGraphQl() || provider.getGraphqlMaxDepth() > 0 || provider.getGraphqlMaxAliases() > 0;
    }

    public static void check(GrantScope scope, GraphQlCall call) {
        if (!scope.narrowsGraphQl()) return;
        // Nothing to check it against. Reached only where the grant narrows nothing about GraphQL,
        // because the inspector refuses an unread document everywhere else; kept as the floor.
        if (call.unreadable())
            throw new GraphQlRefusal(
                    HttpStatus.FORBIDDEN,
                    ErrorCode.GRAPHQL_OPERATION_NOT_GRANTED,
                    "This grant narrows GraphQL, and the operation could not be read");
        for (var operation : call.operations()) {
            if (!scope.admitsOperation(operation.type()))
                throw new GraphQlRefusal(
                        HttpStatus.FORBIDDEN,
                        ErrorCode.GRAPHQL_OPERATION_NOT_GRANTED,
                        "This grant does not admit a " + operation.type().wire() + " on this API");
            for (String field : operation.rootFields())
                if (!scope.admitsRootField(field))
                    throw new GraphQlRefusal(
                            HttpStatus.FORBIDDEN,
                            ErrorCode.GRAPHQL_FIELD_NOT_GRANTED,
                            "This grant does not admit the root field " + field);
        }
    }
}
