package io.janus.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * The traffic ceilings share the {@code janus.gateway} prefix with settings bound elsewhere, and a
 * mistyped key there would silently fall back to a default rather than fail. These bind the same
 * way the application does, so the shipped configuration is checked rather than assumed.
 */
class GatewayTrafficPropertiesTest {

    private static GatewayTrafficProperties bind(Map<String, Object> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("janus.gateway", GatewayTrafficProperties.class)
                .orElseGet(() -> new Binder(new MapConfigurationPropertySource(Map.of()))
                        .bindOrCreate("janus.gateway", GatewayTrafficProperties.class));
    }

    @Test
    void anAbsentSectionStillYieldsWorkingDefaults() {
        var properties = bind(Map.of());
        assertTrue(properties.cache().enabled());
        assertEquals(1000, properties.cache().maxEntries());
        assertEquals(300, properties.cache().staleIfErrorSeconds());
        assertEquals(2000, properties.throttle().maxWaitMillis());
        assertEquals(300, properties.throttle().maxCooldownSeconds());
        assertEquals(2, properties.retry().maxAttempts());
        assertEquals(2000, properties.retry().maxBackoffMillis());
    }

    @Test
    void everyShippedKeyIsBound() {
        var properties = bind(Map.ofEntries(
                Map.entry("janus.gateway.allow-private-destinations", "false"),
                Map.entry("janus.gateway.cache.enabled", "false"),
                Map.entry("janus.gateway.cache.max-entries", "7"),
                Map.entry("janus.gateway.cache.max-entry-bytes", "128"),
                Map.entry("janus.gateway.cache.max-total-bytes", "512"),
                Map.entry("janus.gateway.cache.stale-if-error-seconds", "11"),
                Map.entry("janus.gateway.throttle.max-wait-millis", "13"),
                Map.entry("janus.gateway.throttle.max-cooldown-seconds", "17"),
                Map.entry("janus.gateway.retry.max-attempts", "5"),
                Map.entry("janus.gateway.retry.initial-backoff-millis", "19"),
                Map.entry("janus.gateway.retry.max-backoff-millis", "23")));

        assertFalse(properties.cache().enabled());
        assertEquals(7, properties.cache().maxEntries());
        assertEquals(128, properties.cache().maxEntryBytes());
        assertEquals(512, properties.cache().maxTotalBytes());
        assertEquals(11, properties.cache().staleIfErrorSeconds());
        assertEquals(13, properties.throttle().maxWaitMillis());
        assertEquals(17, properties.throttle().maxCooldownSeconds());
        assertEquals(5, properties.retry().maxAttempts());
        assertEquals(19, properties.retry().initialBackoffMillis());
        assertEquals(23, properties.retry().maxBackoffMillis());
    }

    @Test
    void aPartialSectionKeepsTheOtherDefaults() {
        var properties = bind(Map.of("janus.gateway.cache.max-entries", "42"));
        assertEquals(42, properties.cache().maxEntries());
        assertTrue(properties.cache().enabled());
        assertEquals(2000, properties.throttle().maxWaitMillis());
    }
}
