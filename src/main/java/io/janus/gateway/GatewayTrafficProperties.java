package io.janus.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Deployment-wide ceilings for the three mechanisms that spare a caller from thinking about
 * upstream behaviour: reuse, waiting, and retrying. Per-provider policy decides whether each one
 * applies; these values decide how far it may ever go.
 */
@ConfigurationProperties("janus.gateway")
public record GatewayTrafficProperties(
        @DefaultValue Cache cache, @DefaultValue Throttle throttle, @DefaultValue Retry retry) {

    /**
     * @param enabled             master switch; when false no response is ever stored, whatever a provider says
     * @param maxEntries          number of stored responses before the least recently used one is dropped
     * @param maxEntryBytes       responses larger than this are served but never stored
     * @param maxTotalBytes       total body budget for the store
     * @param staleIfErrorSeconds how long a stale response may still answer when the upstream is failing
     */
    public record Cache(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("1000") int maxEntries,
            @DefaultValue("1048576") int maxEntryBytes,
            @DefaultValue("67108864") long maxTotalBytes,
            @DefaultValue("300") long staleIfErrorSeconds) {}

    /**
     * @param maxWaitMillis      how long a request may wait for a provider allowance before being refused
     * @param maxCooldownSeconds ceiling on a cooldown taken from an upstream {@code Retry-After}
     */
    public record Throttle(@DefaultValue("2000") long maxWaitMillis, @DefaultValue("300") long maxCooldownSeconds) {}

    /**
     * @param maxAttempts           retries after the first attempt, for idempotent methods only
     * @param initialBackoffMillis  first backoff, doubled per attempt and jittered
     * @param maxBackoffMillis      ceiling for one backoff, and for an upstream {@code Retry-After} worth waiting out
     */
    public record Retry(
            @DefaultValue("2") int maxAttempts,
            @DefaultValue("200") long initialBackoffMillis,
            @DefaultValue("2000") long maxBackoffMillis) {}
}
