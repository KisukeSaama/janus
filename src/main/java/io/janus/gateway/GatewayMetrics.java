package io.janus.gateway;

import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import io.janus.audit.AuditOutcome;

/**
 * What the gateway is doing, in numbers.
 *
 * <p>Tags are deliberately low-cardinality: the provider slug, the outcome, and what the store did.
 * The request path is never a tag — one series per URL is how a metrics backend is brought down, and
 * per-path detail is what the audit log is for.
 */
@Component
public class GatewayMetrics {
    private static final String TIMER = "janus.gateway.requests";
    private static final String UNKNOWN = "unknown";

    private final MeterRegistry registry;

    public GatewayMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Records one proxied call.
     *
     * @param providerSlug the destination, or null when the request was refused before one was named
     * @param cacheStatus what the store did, or null when it was never consulted
     */
    public void record(String providerSlug, AuditOutcome outcome, CacheStatus cacheStatus, int status, long nanos) {
        Timer.builder(TIMER)
                .tag("provider", providerSlug == null ? UNKNOWN : providerSlug)
                .tag("outcome", outcome.name())
                .tag("cache", cacheStatus == null ? UNKNOWN : cacheStatus.name())
                .tag("status", Integer.toString(status))
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }
}
