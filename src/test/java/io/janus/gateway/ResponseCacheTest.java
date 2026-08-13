package io.janus.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class ResponseCacheTest {
    private static final UUID PROVIDER = UUID.randomUUID();
    private static final UUID CREDENTIAL = UUID.randomUUID();

    private static ResponseCache cache(int maxEntries, int maxEntryBytes, long maxTotalBytes) {
        return new ResponseCache(new GatewayTrafficProperties(
                new GatewayTrafficProperties.Cache(true, maxEntries, maxEntryBytes, maxTotalBytes, 300),
                new GatewayTrafficProperties.Throttle(2000, 300),
                new GatewayTrafficProperties.Retry(2, 200, 2000),
                new GatewayTrafficProperties.Authorization(true, 10, 100),
                new GatewayTrafficProperties.Transform(true, 2097152)));
    }

    private static ResponseCache.Entry entry(long freshForMillis, byte[] body, Map<String, String> vary) {
        long now = System.currentTimeMillis();
        return new ResponseCache.Entry(
                200,
                HttpHeaders.readOnlyHttpHeaders(new HttpHeaders()),
                body,
                null,
                null,
                now,
                now + freshForMillis,
                now + freshForMillis + 60_000,
                vary);
    }

    private static String key(String path) {
        return CachePolicy.key(
                PROVIDER,
                CREDENTIAL,
                "GET",
                GatewayPath.parse("/gateway/billing" + path, "billing", null),
                new HttpHeaders());
    }

    @Test
    void storesAndReturnsWhatWasStored() {
        var cache = cache(10, 1024, 8192);
        cache.store(key("/v1/orders"), entry(60_000, "hello".getBytes(), Map.of()));

        var found = cache.lookup(key("/v1/orders"), new HttpHeaders());
        assertTrue(found.isPresent());
        assertArrayEquals("hello".getBytes(), found.get().body());
        assertTrue(found.get().fresh(System.currentTimeMillis()));
    }

    @Test
    void anExpiredEntryIsReturnedButNotFresh() {
        var cache = cache(10, 1024, 8192);
        cache.store(key("/v1/orders"), entry(-1000, "old".getBytes(), Map.of()));

        var found = cache.lookup(key("/v1/orders"), new HttpHeaders());
        assertTrue(found.isPresent());
        assertFalse(found.get().fresh(System.currentTimeMillis()));
        assertTrue(found.get().servableStale(System.currentTimeMillis()));
    }

    @Test
    void aRequestThatDisagreesWithVaryIsAMiss() {
        var cache = cache(10, 1024, 8192);
        cache.store(key("/v1/orders"), entry(60_000, "en".getBytes(), Map.of("accept-language", "en")));

        var english = new HttpHeaders();
        english.add(HttpHeaders.ACCEPT_LANGUAGE, "en");
        var french = new HttpHeaders();
        french.add(HttpHeaders.ACCEPT_LANGUAGE, "fr");

        assertTrue(cache.lookup(key("/v1/orders"), english).isPresent());
        assertTrue(cache.lookup(key("/v1/orders"), french).isEmpty());
        assertTrue(cache.lookup(key("/v1/orders"), new HttpHeaders()).isEmpty());
    }

    /**
     * Two representations of one resource, held at once. Keyed on the resource alone they evicted
     * each other on every request, and the store answered nothing while reporting a healthy entry
     * count — the one failure here that is invisible from the outside.
     */
    @Test
    void holdsOneEntryPerRepresentationOfAResourceThatVaries() {
        var cache = cache(10, 1024, 8192);
        var english = new HttpHeaders();
        english.add(HttpHeaders.ACCEPT_LANGUAGE, "en");
        var french = new HttpHeaders();
        french.add(HttpHeaders.ACCEPT_LANGUAGE, "fr");

        cache.store(key("/v1/orders"), entry(60_000, "en".getBytes(), Map.of("accept-language", "en")));
        cache.store(key("/v1/orders"), entry(60_000, "fr".getBytes(), Map.of("accept-language", "fr")));

        assertArrayEquals(
                "en".getBytes(),
                cache.lookup(key("/v1/orders"), english).orElseThrow().body());
        assertArrayEquals(
                "fr".getBytes(),
                cache.lookup(key("/v1/orders"), french).orElseThrow().body());
        assertEquals(2, cache.stats().entries());
        assertEquals(1, cache.stats().variants());
    }

    /** Dropping a resource drops every representation of it, and the names it varied by with them. */
    @Test
    void invalidatingAResourceTakesAllOfItsRepresentations() {
        var cache = cache(10, 1024, 8192);
        var english = new HttpHeaders();
        english.add(HttpHeaders.ACCEPT_LANGUAGE, "en");
        cache.store(key("/v1/orders"), entry(60_000, "en".getBytes(), Map.of("accept-language", "en")));
        cache.store(key("/v1/orders"), entry(60_000, "fr".getBytes(), Map.of("accept-language", "fr")));

        assertEquals(2, cache.invalidateResource(PROVIDER, CREDENTIAL, "/v1/orders"));
        assertTrue(cache.lookup(key("/v1/orders"), english).isEmpty());
        assertEquals(0, cache.stats().entries());
        assertEquals(0, cache.stats().variants());
    }

    @Test
    void aBodyLargerThanTheLimitIsNeverStored() {
        var cache = cache(10, 8, 8192);
        cache.store(key("/v1/orders"), entry(60_000, new byte[64], Map.of()));
        assertTrue(cache.lookup(key("/v1/orders"), new HttpHeaders()).isEmpty());
        // Counted, or a provider whose responses are all too large looks exactly like one nobody
        // calls: no entries, no hits, and no way to tell the two apart.
        assertEquals(1, cache.stats().oversized());
    }

    @Test
    void theOldestUnusedEntryLeavesFirst() {
        var cache = cache(2, 1024, 8192);
        cache.store(key("/a"), entry(60_000, "a".getBytes(), Map.of()));
        cache.store(key("/b"), entry(60_000, "b".getBytes(), Map.of()));
        cache.lookup(key("/a"), new HttpHeaders());
        cache.store(key("/c"), entry(60_000, "c".getBytes(), Map.of()));

        assertTrue(cache.lookup(key("/a"), new HttpHeaders()).isPresent(), "recently read");
        assertTrue(cache.lookup(key("/b"), new HttpHeaders()).isEmpty(), "least recently used");
        assertTrue(cache.lookup(key("/c"), new HttpHeaders()).isPresent(), "just stored");
        assertEquals(2, cache.stats().entries());
    }

    @Test
    void theByteBudgetIsAlsoEnforced() {
        var cache = cache(100, 4096, 3072);
        cache.store(key("/a"), entry(60_000, new byte[1024], Map.of()));
        cache.store(key("/b"), entry(60_000, new byte[1024], Map.of()));
        assertTrue(cache.stats().entries() <= 2);
        assertTrue(cache.stats().bytes() <= 3072);
    }

    @Test
    void aWriteDropsTheResourceAndWhatLivesUnderIt() {
        var cache = cache(10, 1024, 8192);
        cache.store(key("/v1/orders"), entry(60_000, "list".getBytes(), Map.of()));
        cache.store(key("/v1/orders/42"), entry(60_000, "one".getBytes(), Map.of()));
        cache.store(key("/v1/invoices"), entry(60_000, "other".getBytes(), Map.of()));

        assertEquals(2, cache.invalidateResource(PROVIDER, CREDENTIAL, "/v1/orders"));
        assertTrue(cache.lookup(key("/v1/orders"), new HttpHeaders()).isEmpty());
        assertTrue(cache.lookup(key("/v1/orders/42"), new HttpHeaders()).isEmpty());
        assertTrue(cache.lookup(key("/v1/invoices"), new HttpHeaders()).isPresent());
    }

    @Test
    void rotatingACredentialDropsEverythingFetchedWithIt() {
        var cache = cache(10, 1024, 8192);
        cache.store(key("/v1/orders"), entry(60_000, "mine".getBytes(), Map.of()));
        String otherCredential = CachePolicy.key(
                PROVIDER,
                UUID.randomUUID(),
                "GET",
                GatewayPath.parse("/gateway/billing/v1/orders", "billing", null),
                new HttpHeaders());
        cache.store(otherCredential, entry(60_000, "theirs".getBytes(), Map.of()));

        assertEquals(1, cache.invalidateCredential(CREDENTIAL));
        assertTrue(cache.lookup(key("/v1/orders"), new HttpHeaders()).isEmpty());
        assertTrue(cache.lookup(otherCredential, new HttpHeaders()).isPresent());
    }

    @Test
    void aProviderCanBeEmptiedWholesale() {
        var cache = cache(10, 1024, 8192);
        cache.store(key("/v1/orders"), entry(60_000, "a".getBytes(), Map.of()));
        cache.store(key("/v1/invoices"), entry(60_000, "b".getBytes(), Map.of()));
        assertEquals(2, cache.invalidateProvider(PROVIDER));
        assertEquals(0, cache.stats().entries());
        assertEquals(0, cache.stats().bytes());
    }

    @Test
    void aDisabledCacheStoresNothing() {
        var disabled = new ResponseCache(new GatewayTrafficProperties(
                new GatewayTrafficProperties.Cache(false, 10, 1024, 8192, 300),
                new GatewayTrafficProperties.Throttle(2000, 300),
                new GatewayTrafficProperties.Retry(2, 200, 2000),
                new GatewayTrafficProperties.Authorization(true, 10, 100),
                new GatewayTrafficProperties.Transform(true, 2097152)));
        assertFalse(disabled.isEnabled());
        disabled.store(key("/v1/orders"), entry(60_000, "a".getBytes(), Map.of()));
        assertTrue(disabled.lookup(key("/v1/orders"), new HttpHeaders()).isEmpty());
    }

    @Test
    void outcomesAreCounted() {
        var cache = cache(10, 1024, 8192);
        cache.record(CacheStatus.HIT);
        cache.record(CacheStatus.HIT);
        cache.record(CacheStatus.MISS);
        assertEquals(2, cache.stats().outcomes().get("HIT"));
        assertEquals(1, cache.stats().outcomes().get("MISS"));
        assertEquals(0, cache.stats().outcomes().get("STALE"));
    }
}
