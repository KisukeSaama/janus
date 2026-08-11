package io.janus.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import io.janus.audit.AuditOutcome;

class GatewayMetricsTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final GatewayMetrics metrics = new GatewayMetrics(registry);

    @Test
    void recordsOneProxiedCallWithItsDestinationAndOutcome() {
        metrics.record("spotify", AuditOutcome.SUCCESS, CacheStatus.HIT, 200, TimeUnit.MILLISECONDS.toNanos(12));

        var timer = registry.find("janus.gateway.requests")
                .tag("provider", "spotify")
                .tag("outcome", "SUCCESS")
                .tag("cache", "HIT")
                .tag("status", "200")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(12);
    }

    /**
     * A refusal before a provider was resolved has no slug worth naming, and a call that never
     * consulted the store did nothing worth reporting about it. Both are tagged as unknown rather
     * than left out, so every series carries the same set of tags.
     */
    @Test
    void tagsWhatIsNotKnownRatherThanOmittingIt() {
        metrics.record(null, AuditOutcome.DENIED, null, 404, 1);

        assertThat(registry.find("janus.gateway.requests")
                        .tag("provider", "unknown")
                        .tag("cache", "unknown")
                        .timer())
                .isNotNull();
    }

    @Test
    void keepsOneSeriesPerDestinationAndOutcome() {
        metrics.record("spotify", AuditOutcome.SUCCESS, CacheStatus.MISS, 200, 1);
        metrics.record("spotify", AuditOutcome.SUCCESS, CacheStatus.MISS, 200, 1);
        metrics.record("stripe", AuditOutcome.SUCCESS, CacheStatus.MISS, 200, 1);

        assertThat(registry.find("janus.gateway.requests").timers()).hasSize(2);
        assertThat(registry.find("janus.gateway.requests")
                        .tag("provider", "spotify")
                        .timer()
                        .count())
                .isEqualTo(2);
    }
}
