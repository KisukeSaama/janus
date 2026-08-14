package io.janus.gateway;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.janus.credentials.Identity;

class IdentityMemoryTest {
    private final IdentityMemory memory = new IdentityMemory();
    private final UUID credential = UUID.randomUUID();

    @Test
    void anEndpointNeverReachedIsRememberedAsNothing() {
        assertThat(memory.recall(credential, "GET", "/me/playlists")).isEmpty();
    }

    @Test
    void whatWasLearnedIsGivenBack() {
        memory.remember(credential, "GET", "/me/playlists", Identity.ACCOUNT);
        assertThat(memory.recall(credential, "GET", "/me/playlists")).contains(Identity.ACCOUNT);
    }

    /** The point of the template: the second playlist costs nothing, having learned from the first. */
    @Test
    void oneCallTeachesEveryOtherCallToTheSameEndpoint() {
        memory.remember(credential, "GET", "/playlists/3cEYpjA9oz9GiPac4AsH4n", Identity.ACCOUNT);

        assertThat(memory.recall(credential, "GET", "/playlists/6Qs4SXO9dwPj5GKvFOSXuI"))
                .contains(Identity.ACCOUNT);
    }

    @Test
    void aDifferentEndpointIsStillUnknown() {
        memory.remember(credential, "GET", "/me/playlists", Identity.ACCOUNT);
        assertThat(memory.recall(credential, "GET", "/search")).isEmpty();
    }

    /** Reading a resource and writing it can want different identities at some APIs. */
    @Test
    void theMethodIsPartOfWhatIsRemembered() {
        memory.remember(credential, "GET", "/me/playlists", Identity.ACCOUNT);
        assertThat(memory.recall(credential, "POST", "/me/playlists")).isEmpty();
    }

    /**
     * Two accounts may have granted different scopes, so an endpoint one reaches with the
     * application's token is one the other may not.
     */
    @Test
    void nothingIsSharedBetweenCredentials() {
        memory.remember(credential, "GET", "/me/playlists", Identity.ACCOUNT);
        assertThat(memory.recall(UUID.randomUUID(), "GET", "/me/playlists")).isEmpty();
    }

    /** Self-correcting: what an endpoint answers today overwrites what it answered before. */
    @Test
    void aLaterLessonReplacesAnEarlierOne() {
        memory.remember(credential, "GET", "/me/playlists", Identity.ACCOUNT);
        memory.remember(credential, "GET", "/me/playlists", Identity.APP);

        assertThat(memory.recall(credential, "GET", "/me/playlists")).contains(Identity.APP);
    }

    /**
     * The keys are partly chosen by whoever is calling, so the ceiling is what stops a caller filling
     * the heap by asking for a path it has never asked for before.
     */
    @Test
    void theMemoryStaysBoundedHoweverManyEndpointsAreReached() {
        for (int i = 0; i < 120_000; i++)
            memory.remember(credential, "GET", "/section-" + Integer.toHexString(i) + "/items", Identity.ACCOUNT);

        assertThat(memory.tracked()).isLessThanOrEqualTo(IdentityMemory.MAX_TRACKED_ROUTES);
    }

    /** Filling it drops the oldest generation, never what has just been learned. */
    @Test
    void whatWasJustLearnedSurvivesTheRoutesThatCameBeforeIt() {
        for (int i = 0; i < 60_000; i++)
            memory.remember(credential, "GET", "/section-" + Integer.toHexString(i) + "/items", Identity.ACCOUNT);
        memory.remember(credential, "GET", "/me/playlists", Identity.ACCOUNT);

        assertThat(memory.recall(credential, "GET", "/me/playlists")).contains(Identity.ACCOUNT);
    }

    /**
     * An endpoint still being called is kept, whatever else is displacing it: reaching it again is
     * what moves it back into the current generation.
     */
    @Test
    void anEndpointStillBeingReachedIsNotForgotten() {
        memory.remember(credential, "GET", "/me/playlists", Identity.ACCOUNT);
        for (int i = 0; i < 30_000; i++) {
            memory.remember(credential, "GET", "/section-" + Integer.toHexString(i) + "/items", Identity.APP);
            if (i % 1_000 == 0)
                assertThat(memory.recall(credential, "GET", "/me/playlists")).contains(Identity.ACCOUNT);
        }

        assertThat(memory.recall(credential, "GET", "/me/playlists")).contains(Identity.ACCOUNT);
    }

    @Test
    void forgettingOneCredentialLeavesTheOthers() {
        var other = UUID.randomUUID();
        memory.remember(credential, "GET", "/me/playlists", Identity.ACCOUNT);
        memory.remember(other, "GET", "/me/playlists", Identity.ACCOUNT);

        memory.forget(credential);

        assertThat(memory.recall(credential, "GET", "/me/playlists")).isEmpty();
        assertThat(memory.recall(other, "GET", "/me/playlists")).contains(Identity.ACCOUNT);
    }
}
