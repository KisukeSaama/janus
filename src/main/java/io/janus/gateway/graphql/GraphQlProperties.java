package io.janus.gateway.graphql;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Deployment-wide ceilings on what GraphQL traffic may cost this process. What a destination accepts
 * (depth, aliases) is set on the destination; these bound what any of them may hold open.
 *
 * @param streamIdleTimeoutSeconds how long a subscription may go without a single byte from the
 *                                 upstream before it is closed; a live one sends keep-alives far
 *                                 more often than this
 * @param maxStreamSeconds         how long any one subscription may stay open at all; zero is no limit
 * @param maxStreamsPerApplication subscriptions one application may hold open at once, over HTTP and
 *                                 WebSocket together; zero is no limit
 * @param maxOperationsPerSocket   operations one WebSocket may carry at once
 * @param persistedQueries         persisted-query documents remembered across every destination
 * @param maxBatch                 operations one batched request may carry
 */
@ConfigurationProperties("janus.gateway.graphql")
public record GraphQlProperties(
        @DefaultValue("300") long streamIdleTimeoutSeconds,
        @DefaultValue("0") long maxStreamSeconds,
        @DefaultValue("20") int maxStreamsPerApplication,
        @DefaultValue("100") int maxOperationsPerSocket,
        @DefaultValue("5000") int persistedQueries,
        @DefaultValue("25") int maxBatch) {

    /** The defaults, for tests and for anything built outside the container. */
    public static GraphQlProperties defaults() {
        return new GraphQlProperties(300, 0, 20, 100, 5000, 25);
    }
}
