package io.janus.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class ApiKeyCacheTest {
    private final ApiKeyCache cache = new ApiKeyCache();
    private final UUID applicationId = UUID.randomUUID();
    private final GatewayPrincipal principal = new GatewayPrincipal(UUID.randomUUID(), "orders", UUID.randomUUID());

    @Test
    void returnsNothingBeforeAnythingIsStored() {
        assertThat(cache.lookup(applicationId, "jns_key", "hash")).isEmpty();
    }

    @Test
    void returnsTheStoredPrincipalForTheSameKey() {
        cache.store(applicationId, "jns_key", "hash", principal);
        assertThat(cache.lookup(applicationId, "jns_key", "hash")).contains(principal);
    }

    @Test
    void aDifferentKeyDoesNotHitTheEntry() {
        cache.store(applicationId, "jns_key", "hash", principal);
        assertThat(cache.lookup(applicationId, "jns_other", "hash")).isEmpty();
    }

    @Test
    void aRotatedKeyInvalidatesTheEntryThroughTheStoredHash() {
        cache.store(applicationId, "jns_key", "hash", principal);
        assertThat(cache.lookup(applicationId, "jns_key", "rotated-hash")).isEmpty();
        // The stale entry is dropped rather than left to be re-checked on every request.
        assertThat(cache.lookup(applicationId, "jns_key", "hash")).isEmpty();
    }

    @Test
    void entriesAreScopedToOneApplication() {
        cache.store(applicationId, "jns_key", "hash", principal);
        assertThat(cache.lookup(UUID.randomUUID(), "jns_key", "hash")).isEmpty();
    }

    @Test
    void invalidationRemovesEveryEntryForAnApplication() {
        cache.store(applicationId, "jns_key", "hash", principal);
        cache.store(applicationId, "jns_second", "hash", principal);
        cache.invalidate(applicationId);
        assertThat(cache.lookup(applicationId, "jns_key", "hash")).isEmpty();
        assertThat(cache.lookup(applicationId, "jns_second", "hash")).isEmpty();
    }
}
