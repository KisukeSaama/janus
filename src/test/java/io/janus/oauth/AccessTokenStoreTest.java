package io.janus.oauth;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.janus.security.GatewayPrincipal;

class AccessTokenStoreTest {

    private static AccessTokenStore store(int maxEntries) {
        return new AccessTokenStore(
                new OAuthProperties(Duration.ofMinutes(15), Duration.ofDays(30), true, maxEntries, 0));
    }

    private static GatewayPrincipal caller() {
        return new GatewayPrincipal(UUID.randomUUID(), "orders", UUID.randomUUID(), Set.of());
    }

    @Test
    void issuesAPrefixedTokenThatResolvesBackToItsCaller() {
        var store = store(10);
        var principal = caller();

        String token = store.issue(principal, 60);

        assertThat(token).startsWith(AccessTokenStore.PREFIX);
        assertThat(store.resolve(token)).contains(principal);
    }

    /** Two exchanges must never collide, whatever else is true. */
    @Test
    void everyTokenIsDistinct() {
        var store = store(10);
        assertThat(store.issue(caller(), 60)).isNotEqualTo(store.issue(caller(), 60));
    }

    @Test
    void anExpiredTokenResolvesToNothing() throws Exception {
        var store = store(10);
        String token = store.issue(caller(), 0);
        Thread.sleep(2);
        assertThat(store.resolve(token)).isEmpty();
    }

    @Test
    void anUnknownOrEmptyTokenResolvesToNothing() {
        var store = store(10);
        assertThat(store.resolve("jnt_never-issued")).isEmpty();
        assertThat(store.resolve("")).isEmpty();
        assertThat(store.resolve(null)).isEmpty();
    }

    /** Revocation is the reason these are opaque rather than signed: it has to be immediate. */
    @Test
    void aRevokedTokenStopsWorkingAtOnce() {
        var store = store(10);
        String token = store.issue(caller(), 600);

        assertThat(store.revoke(token)).isTrue();
        assertThat(store.resolve(token)).isEmpty();
        assertThat(store.revoke(token)).isFalse();
    }

    /**
     * What a key rotation relies on: every token an application was ever handed goes at once, and
     * nobody else's does.
     */
    @Test
    void revokingAnApplicationDropsItsTokensAndOnlyItsTokens() {
        var store = store(10);
        var mine = caller();
        var theirs = caller();
        String first = store.issue(mine, 600);
        String second = store.issue(mine, 600);
        String other = store.issue(theirs, 600);

        assertThat(store.revokeApplication(mine.applicationId())).isEqualTo(2);
        assertThat(store.resolve(first)).isEmpty();
        assertThat(store.resolve(second)).isEmpty();
        assertThat(store.resolve(other)).contains(theirs);
    }

    /** Bounded, like every other in-memory store here: a client in a loop must not exhaust the heap. */
    @Test
    void theOldestTokensAreDroppedOnceTheStoreIsFull() {
        var store = store(2);
        String first = store.issue(caller(), 600);
        store.issue(caller(), 600);
        store.issue(caller(), 600);

        assertThat(store.resolve(first)).isEmpty();
    }

    /**
     * The bound one application reaches must be its own. A single least-recently-used ceiling over
     * the whole deployment made a caller asking for tokens in a loop — well inside its rate limit —
     * everybody else's problem: their tokens went, and each of them found itself unauthenticated for
     * no reason it could see.
     */
    @Test
    void oneApplicationInALoopEvictsItsOwnTokensAndNobodyElses() {
        var store =
                new AccessTokenStore(new OAuthProperties(Duration.ofMinutes(15), Duration.ofDays(30), true, 100, 2));
        var quiet = caller();
        String theirs = store.issue(quiet, 600);
        var noisy = caller();

        String kept = null;
        for (int i = 0; i < 20; i++) kept = store.issue(noisy, 600);

        assertThat(store.resolve(theirs))
                .as("the quiet caller keeps what it was given")
                .contains(quiet);
        assertThat(store.resolve(kept))
                .as("the noisy one keeps its most recent")
                .contains(noisy);
        assertThat(store.revokeApplication(noisy.applicationId()))
                .as("and holds no more than its own ceiling")
                .isEqualTo(2);
    }

    /**
     * Expired entries are dead weight, so they are what a full store gives up first. Without that,
     * the oldest live token goes while a token nobody can present any more keeps its place.
     */
    @Test
    void whatHasExpiredIsGivenUpBeforeAnythingStillValid() throws Exception {
        var store = store(3);
        var oldest = caller();
        String earliest = store.issue(oldest, 600);
        store.issue(caller(), 600);
        store.issue(caller(), 1);
        Thread.sleep(1100);

        // The store is at its bound and something has to go. Least recently used would take the one
        // issued first; what actually goes is the one that has run out.
        store.issue(caller(), 600);

        assertThat(store.resolve(earliest)).contains(oldest);
    }
}
