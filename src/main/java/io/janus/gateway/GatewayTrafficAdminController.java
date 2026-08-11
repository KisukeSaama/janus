package io.janus.gateway;

import java.time.Instant;
import java.util.*;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import io.janus.accounts.AccessScope;
import io.janus.audit.AuditAction;
import io.janus.audit.AuditService;
import io.janus.providers.ProviderRepository;

/**
 * What the gateway is currently doing on the callers' behalf: what it is holding, how often that
 * spared an upstream call, and which providers have asked to be left alone.
 *
 * <p>Read-only apart from the purge, which exists for the one case configuration cannot cover —
 * data changed at the provider without Janus having made the change.
 */
@RestController
@RequestMapping("/api/admin/gateway")
public class GatewayTrafficAdminController {
    private final TrafficPolicyRegistry traffic;
    private final ProviderRepository providers;
    private final AccessScope scope;
    private final AuditService audit;

    public GatewayTrafficAdminController(
            TrafficPolicyRegistry traffic, ProviderRepository providers, AccessScope scope, AuditService audit) {
        this.traffic = traffic;
        this.providers = providers;
        this.scope = scope;
        this.audit = audit;
    }

    /**
     * @param outcomes  count of requests per {@code X-Janus-Cache} outcome since start-up
     * @param hitRatio  share of cacheable requests answered without calling the provider
     */
    public record CacheReport(
            boolean enabled,
            int entries,
            long bytes,
            int maxEntries,
            long maxBytes,
            long stores,
            long evictions,
            Map<String, Long> outcomes,
            double hitRatio) {}

    public record CooldownReport(
            UUID providerId, String providerName, String providerSlug, Instant until, int status) {}

    public record Report(CacheReport cache, List<CooldownReport> cooldowns) {}

    /**
     * What the gateway is holding, and which of the caller's own destinations have asked to be left
     * alone.
     *
     * <p>The cache figures are process-wide and name nobody: they count entries and bytes, not whose.
     * The cooldowns do name destinations, so they are narrowed to the caller's own — a pause on
     * somebody else's API is not theirs to read, and not theirs to act on either.
     */
    @GetMapping("/traffic")
    @Transactional(readOnly = true)
    public Report traffic() {
        var snapshot = traffic.snapshot();
        var stats = snapshot.cache();
        var byId = new HashMap<UUID, io.janus.providers.Provider>();
        providers.findAllByOwnerId(scope.ownerFilter()).forEach(provider -> byId.put(provider.getId(), provider));

        long spared = count(stats, CacheStatus.HIT)
                + count(stats, CacheStatus.STALE)
                + count(stats, CacheStatus.REVALIDATED)
                + count(stats, CacheStatus.COALESCED);
        long considered = spared + count(stats, CacheStatus.MISS);

        var cache = new CacheReport(
                stats.enabled(),
                stats.entries(),
                stats.bytes(),
                stats.maxEntries(),
                stats.maxBytes(),
                stats.stores(),
                stats.evictions(),
                stats.outcomes(),
                considered == 0 ? 0 : (double) spared / considered);
        var cooldowns = snapshot.cooldowns().stream()
                .filter(pause -> byId.containsKey(pause.providerId()))
                .map(pause -> {
                    var provider = byId.get(pause.providerId());
                    return new CooldownReport(
                            pause.providerId(), provider.getName(), provider.getSlug(), pause.until(), pause.status());
                })
                .toList();
        return new Report(cache, cooldowns);
    }

    /** Drops every stored response, for every provider. */
    @DeleteMapping("/cache")
    public Map<String, Object> purge() {
        int dropped = traffic.purgeCache();
        audit.recordAdmin(AuditAction.GATEWAY_CACHE_PURGED, null, dropped + " entries");
        return Map.of("purged", dropped);
    }

    private static long count(ResponseCache.Stats stats, CacheStatus status) {
        return stats.outcomes().getOrDefault(status.name(), 0L);
    }
}
