package io.janus.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.*;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;

import io.janus.credentials.Identity;
import io.janus.credentials.UpstreamTokenCache;
import io.janus.testing.Fixtures;

/**
 * The seam between administration and the running gateway. Every test here is really the same
 * question: after an administrator changes something, is the old policy actually gone?
 */
class TrafficPolicyRegistryTest {
    private final GatewayTrafficProperties properties = new GatewayTrafficProperties(
            new GatewayTrafficProperties.Cache(true, 100, 1_000_000, 10_000_000, 300),
            new GatewayTrafficProperties.Throttle(1, 300),
            new GatewayTrafficProperties.Retry(2, 1, 1),
            new GatewayTrafficProperties.Authorization(true, 10, 100),
            new GatewayTrafficProperties.Transform(true, 2097152));

    private final ResponseCache cache = new ResponseCache(properties);
    private final AuthorizationCache authorizations = new AuthorizationCache(properties);
    private final RateLimiter limiter = new RateLimiter();
    private final UpstreamCooldown cooldown = new UpstreamCooldown();
    private final UpstreamTokenCache tokens = Mockito.mock(UpstreamTokenCache.class);

    private final TrafficPolicyRegistry registry =
            new TrafficPolicyRegistry(cache, authorizations, limiter, cooldown, tokens, new IdentityMemory());

    private final UUID provider = UUID.randomUUID();
    private final UUID credential = UUID.randomUUID();

    private void store(UUID providerId, UUID credentialId, String path) {
        long now = System.currentTimeMillis();
        var route = GatewayPath.parse("/gateway/slug" + path, "slug", null);
        var key = CachePolicy.key(providerId, credentialId, Identity.APP, "GET", route, new HttpHeaders());
        cache.store(
                key,
                new ResponseCache.Entry(
                        200, HttpHeaders.EMPTY, new byte[0], null, null, now, now + 60_000, now + 60_000, Map.of()));
    }

    @Test
    void forgettingAProviderDropsItsStoredResponsesItsBucketAndItsCooldown() {
        store(provider, credential, "/v1/tracks");
        var other = UUID.randomUUID();
        store(other, credential, "/v1/tracks");
        cooldown.pause(UpstreamCooldown.key(provider, credential), provider, 429, 300);

        int dropped = registry.forgetProvider(provider);

        assertThat(dropped).isEqualTo(1);
        assertThat(cache.stats().entries()).isEqualTo(1);
        assertThat(cooldown.remaining(UpstreamCooldown.key(provider, credential)))
                .isEmpty();
    }

    /**
     * A rotated client secret leaves behind a token the provider may keep honouring for an hour.
     * Dropping the responses without dropping that token would take the credential back everywhere
     * except where it is actually being used.
     */
    @Test
    void forgettingACredentialDropsItsHeldTokenAsWellAsItsStoredResponses() {
        store(provider, credential, "/v1/tracks");

        int dropped = registry.forgetCredential(credential);

        assertThat(dropped).isEqualTo(1);
        verify(tokens).invalidate(credential);
    }

    @Test
    void forgettingAGrantResetsItsAllowanceWithoutTouchingTheStore() {
        store(provider, credential, "/v1/tracks");
        var grant = UUID.randomUUID();
        limiter.tryAcquire("grant:" + grant, 1, 1);

        registry.forgetGrant(grant);

        assertThat(limiter.tryAcquire("grant:" + grant, 1, 1).allowed()).isTrue();
        assertThat(cache.stats().entries()).isEqualTo(1);
    }

    /**
     * The registry reads go with everything else. A grant resolved four seconds ago is what would
     * carry a withdrawn credential onto the next request, so a change that dropped the responses and
     * left the grant behind would take the secret back everywhere except on the way out.
     */
    @Test
    void forgettingACredentialAlsoDropsTheGrantsThatWouldPresentIt() {
        var owner = Fixtures.owner();
        var destination = Fixtures.provider(owner);
        var held = Fixtures.grant(Fixtures.application(owner), destination, Fixtures.credential(destination));
        authorizations.grant(UUID.randomUUID(), provider, () -> Optional.of(held));

        registry.forgetCredential(held.getCredential().getId());

        assertThat(authorizations.stats().grants()).isZero();
    }

    @Test
    void purgingEmptiesTheWholeStore() {
        store(provider, credential, "/v1/tracks");
        store(UUID.randomUUID(), UUID.randomUUID(), "/v1/albums");

        assertThat(registry.purgeCache()).isEqualTo(2);
        assertThat(cache.stats().entries()).isZero();
    }

    @Test
    void reportsWhatIsStoredAndWhichProvidersAreRefusingTraffic() {
        store(provider, credential, "/v1/tracks");
        cooldown.pause(UpstreamCooldown.key(provider, credential), provider, 503, 300);

        var snapshot = registry.snapshot();

        assertThat(snapshot.cache().entries()).isEqualTo(1);
        assertThat(snapshot.cooldowns()).singleElement().satisfies(pause -> {
            assertThat(pause.providerId()).isEqualTo(provider);
            assertThat(pause.status()).isEqualTo(503);
        });
    }

    @Test
    void listsCooldownsSoonestFirst() {
        var soon = UUID.randomUUID();
        var later = UUID.randomUUID();
        cooldown.pause(UpstreamCooldown.key(later, credential), later, 429, 300);
        cooldown.pause(UpstreamCooldown.key(soon, credential), soon, 429, 30);

        assertThat(registry.snapshot().cooldowns())
                .extracting(TrafficPolicyRegistry.Cooldown::providerId)
                .containsExactly(soon, later);
    }
}
