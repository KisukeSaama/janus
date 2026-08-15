package io.janus.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class UpstreamCooldownTest {
    private final UpstreamCooldown cooldown = new UpstreamCooldown();
    private final UUID provider = UUID.randomUUID();
    private final UUID credential = UUID.randomUUID();
    private final String key = UpstreamCooldown.key(provider, credential);

    @Test
    void reportsHowLongIsLeftToWait() {
        cooldown.pause(key, provider, 429, 120);

        assertThat(cooldown.remaining(key))
                .hasValueSatisfying(seconds -> assertThat(seconds).isBetween(118L, 120L));
    }

    @Test
    void aProviderThatWasNeverPausedIsFreeToBeCalled() {
        assertThat(cooldown.remaining(key)).isEmpty();
    }

    /** A pause that has run out is not a pause, and does not stay in the table pretending to be one. */
    @Test
    void anExpiredPauseIsForgottenWhenItIsAskedAbout() throws InterruptedException {
        cooldown.pause(key, provider, 503, 1);
        assertThat(cooldown.remaining(key)).isPresent();

        // A pause is stated in whole seconds, so this waits out the shortest one there can be.
        for (int i = 0; i < 40 && cooldown.remaining(key).isPresent(); i++) Thread.sleep(50);

        assertThat(cooldown.remaining(key)).isEmpty();
        assertThat(cooldown.active()).isEmpty();
    }

    @Test
    void aPauseOfNoTimeIsNotRecorded() {
        cooldown.pause(key, provider, 429, 0);

        assertThat(cooldown.remaining(key)).isEmpty();
        assertThat(cooldown.active()).isEmpty();
    }

    /**
     * Two callers can hit the same limit moments apart. Keeping the longer of the two answers means
     * the second, shorter one cannot shorten a pause the provider already asked for.
     */
    @Test
    void aShorterPauseNeverShortensOneAlreadyInForce() {
        cooldown.pause(key, provider, 429, 300);
        cooldown.pause(key, provider, 503, 5);

        assertThat(cooldown.remaining(key))
                .hasValueSatisfying(seconds -> assertThat(seconds).isGreaterThan(200L));
    }

    @Test
    void aLongerPauseReplacesTheOneInForce() {
        cooldown.pause(key, provider, 503, 5);
        cooldown.pause(key, provider, 429, 300);

        assertThat(cooldown.remaining(key))
                .hasValueSatisfying(seconds -> assertThat(seconds).isGreaterThan(200L));
    }

    /** Each credential is its own client as far as the provider is concerned. */
    @Test
    void pausingOneCredentialDoesNotPauseAnother() {
        cooldown.pause(key, provider, 429, 300);

        assertThat(cooldown.remaining(UpstreamCooldown.key(provider, UUID.randomUUID())))
                .isEmpty();
    }

    @Test
    void clearingAProviderLiftsEveryPauseHeldAgainstIt() {
        cooldown.pause(key, provider, 429, 300);
        cooldown.pause(UpstreamCooldown.key(provider, UUID.randomUUID()), provider, 429, 300);
        var other = UUID.randomUUID();
        cooldown.pause(UpstreamCooldown.key(other, credential), other, 429, 300);

        cooldown.clearProvider(provider);

        assertThat(cooldown.active())
                .singleElement()
                .satisfies(pause -> assertThat(pause.providerId()).isEqualTo(other));
    }

    @Test
    void resumingLiftsOnePauseAndResetLiftsThemAll() {
        cooldown.pause(key, provider, 429, 300);
        cooldown.resume(key);
        assertThat(cooldown.remaining(key)).isEmpty();

        cooldown.pause(key, provider, 429, 300);
        cooldown.reset();
        assertThat(cooldown.active()).isEmpty();
    }
}
