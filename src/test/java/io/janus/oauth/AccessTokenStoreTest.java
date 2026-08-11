package io.janus.oauth;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.janus.security.GatewayPrincipal;

class AccessTokenStoreTest {

    private static AccessTokenStore store(int maxEntries) {
        return new AccessTokenStore(new OAuthProperties(Duration.ofMinutes(15), Duration.ofDays(30), true, maxEntries));
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
}
