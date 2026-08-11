package io.janus.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.janus.credentials.Credential;
import io.janus.grants.Grant;
import io.janus.providers.Provider;
import io.janus.testing.Fixtures;

/**
 * The registry reads behind an authorised call, held for a few seconds.
 *
 * <p>Two questions run through every test here. Does a caller stop paying for the same lookup on
 * every request — and, the one that matters more, does an administrative change still take effect at
 * once? A cache that answers the first well and the second late is not a faster gateway, it is a
 * gateway that honours revoked access.
 */
class AuthorizationCacheTest {

    private static AuthorizationCache cache(boolean enabled, long ttlSeconds) {
        return new AuthorizationCache(new GatewayTrafficProperties(
                new GatewayTrafficProperties.Cache(true, 100, 1_000_000, 10_000_000, 300),
                new GatewayTrafficProperties.Throttle(2000, 300),
                new GatewayTrafficProperties.Retry(2, 200, 2000),
                new GatewayTrafficProperties.Authorization(enabled, ttlSeconds, 100)));
    }

    private final io.janus.accounts.Account owner = Fixtures.owner();
    private final Provider provider = Fixtures.provider(owner);
    private final Credential credential = Fixtures.credential(provider);
    private final io.janus.applications.Application application = Fixtures.application(owner);
    private final Grant grant = Fixtures.grant(application, provider, credential);

    private final AtomicInteger reads = new AtomicInteger();

    private Optional<Provider> loadProvider() {
        reads.incrementAndGet();
        return Optional.of(provider);
    }

    private Optional<Grant> loadGrant() {
        reads.incrementAndGet();
        return Optional.of(grant);
    }

    @Test
    void resolvesADestinationOnceForAsLongAsItIsHeld() {
        var cache = cache(true, 10);

        cache.provider("billing", this::loadProvider);
        var second = cache.provider("billing", this::loadProvider);

        assertThat(second).contains(provider);
        assertThat(reads.get()).isEqualTo(1);
    }

    @Test
    void resolvesAGrantOnceForAsLongAsItIsHeld() {
        var cache = cache(true, 10);

        cache.grant(application.getId(), provider.getId(), this::loadGrant);
        var second = cache.grant(application.getId(), provider.getId(), this::loadGrant);

        assertThat(second).contains(grant);
        assertThat(reads.get()).isEqualTo(1);
    }

    /**
     * Two applications asking about the same destination are two questions, and the deployment shares
     * one catalogue of APIs between every account. An entry keyed on the destination alone would
     * answer one account's caller with another account's grant — and the credential inside it.
     */
    @Test
    void neverAnswersOneApplicationWithAnotherApplicationsGrant() {
        var cache = cache(true, 10);
        var otherOwner = Fixtures.owner();
        var otherApplication = Fixtures.application(otherOwner);
        // The catalogue is shared, so this is the same destination: what differs is whose secret is
        // provisioned against it. Exactly the shape production has once two accounts activate one API.
        var otherCredential =
                new Credential(otherOwner.getId(), provider, "theirs", Credential.strategyOf(provider), null, true);
        var otherGrant = Fixtures.grant(otherApplication, provider, otherCredential);

        cache.grant(application.getId(), provider.getId(), this::loadGrant);
        var theirs = cache.grant(otherApplication.getId(), provider.getId(), () -> Optional.of(otherGrant));

        assertThat(theirs).contains(otherGrant);
        assertThat(theirs.orElseThrow().getCredential()).isEqualTo(otherCredential);
    }

    /**
     * Refusals are never held. A caller cannot fill the store with invented slugs, and a grant that
     * starts existing is honoured on the next request rather than at the end of a timeout.
     */
    @Test
    void remembersNothingAboutWhatDidNotResolve() {
        var cache = cache(true, 10);

        cache.provider("invented", () -> {
            reads.incrementAndGet();
            return Optional.empty();
        });
        cache.provider("invented", () -> {
            reads.incrementAndGet();
            return Optional.empty();
        });

        assertThat(reads.get()).isEqualTo(2);
        assertThat(cache.stats().providers()).isZero();
    }

    @Test
    void anExpiredEntryIsResolvedAgain() throws InterruptedException {
        var cache = cache(true, 1);

        cache.provider("billing", this::loadProvider);
        Thread.sleep(1100);
        cache.provider("billing", this::loadProvider);

        assertThat(reads.get()).isEqualTo(2);
    }

    // --- what an administrative change has to reach --------------------------

    @Test
    void changingADestinationDropsItAndEveryGrantLeadingToIt() {
        var cache = cache(true, 10);
        cache.provider("billing", this::loadProvider);
        cache.grant(application.getId(), provider.getId(), this::loadGrant);

        cache.forgetProvider(provider.getId());

        assertThat(cache.stats().providers()).isZero();
        assertThat(cache.stats().grants()).isZero();
    }

    @Test
    void withdrawingAGrantDropsIt() {
        var cache = cache(true, 10);
        cache.grant(application.getId(), provider.getId(), this::loadGrant);

        cache.forgetGrant(grant.getId());

        assertThat(cache.stats().grants()).isZero();
    }

    /**
     * A rotated or disabled secret has to stop being usable at once, and the grant is what carries it
     * onto the request — including grants belonging to other applications of the same account.
     */
    @Test
    void rotatingACredentialDropsEveryGrantCarryingIt() {
        var cache = cache(true, 10);
        var secondApplication = Fixtures.application(owner);
        var sharedGrant = Fixtures.grant(secondApplication, provider, credential);
        cache.grant(application.getId(), provider.getId(), this::loadGrant);
        cache.grant(secondApplication.getId(), provider.getId(), () -> Optional.of(sharedGrant));

        cache.forgetCredential(credential.getId());

        assertThat(cache.stats().grants()).isZero();
    }

    @Test
    void aGrantOnAnotherCredentialIsLeftAlone() {
        var cache = cache(true, 10);
        cache.grant(application.getId(), provider.getId(), this::loadGrant);

        cache.forgetCredential(UUID.randomUUID());

        assertThat(cache.stats().grants()).isEqualTo(1);
    }

    @Test
    void whenDisabledEveryCallReadsTheRegistryAgain() {
        var cache = cache(false, 10);

        cache.provider("billing", this::loadProvider);
        cache.provider("billing", this::loadProvider);

        assertThat(reads.get()).isEqualTo(2);
        assertThat(cache.stats().enabled()).isFalse();
    }

    @Test
    void reportsWhatItSparedTheDatabase() {
        var cache = cache(true, 10);

        cache.provider("billing", this::loadProvider);
        cache.provider("billing", this::loadProvider);
        cache.provider("billing", this::loadProvider);

        assertThat(cache.stats().misses()).isEqualTo(1);
        assertThat(cache.stats().hits()).isEqualTo(2);
    }
}
