package io.janus.gateway;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Remembers that an upstream asked to be left alone.
 *
 * <p>When a provider answers 429 or 503 with a {@code Retry-After} longer than a retry could
 * reasonably absorb, continuing to send it traffic is how a short rate-limit turns into a long ban.
 * Janus holds the door instead: for the stated delay, every caller of that provider is answered
 * immediately — from the store when something is stored, with the same {@code Retry-After}
 * otherwise — and not one further request is sent upstream.
 */
@Component
public class UpstreamCooldown {

    /** @param until when traffic may resume, @param status the upstream status that caused it */
    public record Pause(UUID providerId, Instant until, int status) {}

    private final Map<String, Pause> pauses = new ConcurrentHashMap<>();

    public static String key(UUID providerId, UUID credentialId) {
        return providerId + ":" + credentialId;
    }

    /** Seconds still to wait, or empty when this provider and credential are free to be called. */
    public Optional<Long> remaining(String key) {
        var pause = pauses.get(key);
        if (pause == null) return Optional.empty();
        long seconds = pause.until().getEpochSecond() - Instant.now().getEpochSecond();
        if (seconds <= 0) {
            pauses.remove(key, pause);
            return Optional.empty();
        }
        return Optional.of(seconds);
    }

    public void pause(String key, UUID providerId, int status, long seconds) {
        if (seconds <= 0) return;
        var until = Instant.now().plusSeconds(seconds);
        pauses.merge(
                key,
                new Pause(providerId, until, status),
                (current, proposed) -> current.until().isAfter(proposed.until()) ? current : proposed);
    }

    public void resume(String key) {
        pauses.remove(key);
    }

    /** Every pause still in force, for the administration console. */
    public List<Pause> active() {
        var now = Instant.now();
        pauses.values().removeIf(pause -> pause.until().isBefore(now));
        return List.copyOf(pauses.values());
    }

    public void clearProvider(UUID providerId) {
        pauses.values().removeIf(pause -> pause.providerId().equals(providerId));
    }

    public void reset() {
        pauses.clear();
    }
}
