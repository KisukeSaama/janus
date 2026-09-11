package io.janus.gateway;

import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import io.janus.audit.AuditOutcome;
import io.janus.gateway.graphql.OperationType;

/**
 * What the gateway is doing, in numbers.
 *
 * <p>Tags are deliberately low-cardinality: the provider slug, the outcome, what the store did, and
 * the kind of GraphQL operation. The request path is never a tag — one series per URL is how a
 * metrics backend is brought down, and per-path detail is what the audit log is for. The GraphQL
 * operation's name is never one either, for the same reason: the caller chooses it.
 */
@Component
public class GatewayMetrics {
    private static final String TIMER = "janus.gateway.requests";
    private static final String UNKNOWN = "unknown";
    private static final String NONE = "none";

    private final MeterRegistry registry;

    public GatewayMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Records one call that was not a GraphQL operation. */
    public void record(String providerSlug, AuditOutcome outcome, CacheStatus cacheStatus, int status, long nanos) {
        record(providerSlug, outcome, cacheStatus, status, nanos, null);
    }

    /**
     * Records one proxied call.
     *
     * @param providerSlug the destination, or null when the request was refused before one was named
     * @param cacheStatus what the store did, or null when it was never consulted
     * @param operation the kind of GraphQL operation, or null when the call was not one; every series
     *     carries the tag, so a backend that insists on one set of tags per name gets one
     */
    public void record(
            String providerSlug,
            AuditOutcome outcome,
            CacheStatus cacheStatus,
            int status,
            long nanos,
            OperationType operation) {
        Timer.builder(TIMER)
                .tag("provider", providerSlug == null ? UNKNOWN : providerSlug)
                .tag("outcome", outcome.name())
                .tag("cache", cacheStatus == null ? UNKNOWN : cacheStatus.name())
                .tag("status", Integer.toString(status))
                .tag("graphql", operation == null ? NONE : operation.wire())
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }
}
