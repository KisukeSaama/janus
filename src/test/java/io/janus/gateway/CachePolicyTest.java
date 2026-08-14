package io.janus.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import io.janus.credentials.Identity;

import org.junit.jupiter.api.Test;
import org.springframework.http.*;

class CachePolicyTest {
    private static final UUID PROVIDER = UUID.randomUUID();
    private static final UUID CREDENTIAL = UUID.randomUUID();
    private static final long STALE_DEFAULT = 300;

    private static HttpHeaders headers(String... pairs) {
        var headers = new HttpHeaders();
        for (int i = 0; i < pairs.length; i += 2) headers.add(pairs[i], pairs[i + 1]);
        return headers;
    }

    private static GatewayPath route(String path) {
        return GatewayPath.parse("/gateway/billing" + path, "billing", null);
    }

    /* ── What may be stored ─────────────────────────────────────────────── */

    @Test
    void anUpstreamMaxAgeSetsTheFreshnessLifetime() {
        var storability =
                CachePolicy.evaluate(HttpStatus.OK, headers("Cache-Control", "max-age=120"), 0, STALE_DEFAULT);
        assertTrue(storability.storable());
        assertEquals(120, storability.freshSeconds());
        assertEquals(STALE_DEFAULT, storability.staleSeconds());
    }

    @Test
    void sharedMaxAgeWinsOverMaxAge() {
        var storability = CachePolicy.evaluate(
                HttpStatus.OK, headers("Cache-Control", "max-age=10, s-maxage=90"), 0, STALE_DEFAULT);
        assertEquals(90, storability.freshSeconds());
    }

    @Test
    void nothingIsStoredWithoutADirectiveOrAProviderDefault() {
        assertFalse(CachePolicy.evaluate(HttpStatus.OK, new HttpHeaders(), 0, STALE_DEFAULT)
                .storable());
    }

    @Test
    void theProviderDefaultOnlyAppliesWhenTheUpstreamIsSilent() {
        assertEquals(
                60,
                CachePolicy.evaluate(HttpStatus.OK, new HttpHeaders(), 60, STALE_DEFAULT)
                        .freshSeconds());
        assertEquals(
                5,
                CachePolicy.evaluate(HttpStatus.OK, headers("Cache-Control", "max-age=5"), 60, STALE_DEFAULT)
                        .freshSeconds());
    }

    @Test
    void privateAndNoStoreAreNeverStored() {
        assertFalse(
                CachePolicy.evaluate(HttpStatus.OK, headers("Cache-Control", "private, max-age=600"), 60, STALE_DEFAULT)
                        .storable());
        assertFalse(CachePolicy.evaluate(HttpStatus.OK, headers("Cache-Control", "no-store"), 60, STALE_DEFAULT)
                .storable());
    }

    @Test
    void aResponseThatSetsACookieIsNeverStored() {
        assertFalse(CachePolicy.evaluate(
                        HttpStatus.OK,
                        headers("Set-Cookie", "session=abc", "Cache-Control", "max-age=600"),
                        60,
                        STALE_DEFAULT)
                .storable());
    }

    @Test
    void varyingOnEverythingIsNeverStored() {
        assertFalse(CachePolicy.evaluate(
                        HttpStatus.OK, headers("Vary", "*", "Cache-Control", "max-age=600"), 60, STALE_DEFAULT)
                .storable());
    }

    @Test
    void noCacheIsStoredButNeverFresh() {
        var storability = CachePolicy.evaluate(HttpStatus.OK, headers("Cache-Control", "no-cache"), 600, STALE_DEFAULT);
        assertTrue(storability.storable());
        assertEquals(0, storability.freshSeconds());
    }

    @Test
    void mustRevalidateForbidsServingStale() {
        var storability = CachePolicy.evaluate(
                HttpStatus.OK, headers("Cache-Control", "max-age=30, must-revalidate"), 0, STALE_DEFAULT);
        assertEquals(0, storability.staleSeconds());
    }

    @Test
    void anUpstreamStaleIfErrorOverridesTheDefault() {
        var storability = CachePolicy.evaluate(
                HttpStatus.OK, headers("Cache-Control", "max-age=30, stale-if-error=60"), 0, STALE_DEFAULT);
        assertEquals(60, storability.staleSeconds());
    }

    @Test
    void serverFailuresAreNotStored() {
        assertFalse(CachePolicy.evaluate(
                        HttpStatus.INTERNAL_SERVER_ERROR, headers("Cache-Control", "max-age=600"), 60, STALE_DEFAULT)
                .storable());
        assertFalse(CachePolicy.evaluate(
                        HttpStatus.TOO_MANY_REQUESTS, headers("Cache-Control", "max-age=600"), 60, STALE_DEFAULT)
                .storable());
    }

    @Test
    void aMissingResourceIsStoredOnlyForTheStatedTime() {
        assertTrue(CachePolicy.evaluate(HttpStatus.NOT_FOUND, headers("Cache-Control", "max-age=30"), 0, STALE_DEFAULT)
                .storable());
    }

    @Test
    void anExpiresHeaderInThePastMeansAlreadyStale() {
        var storability = CachePolicy.evaluate(
                HttpStatus.OK,
                headers("Date", "Wed, 21 Oct 2015 07:28:00 GMT", "Expires", "Wed, 21 Oct 2015 07:28:00 GMT"),
                60,
                STALE_DEFAULT);
        assertTrue(storability.storable());
        assertEquals(0, storability.freshSeconds());
    }

    /* ── What the caller asked for ──────────────────────────────────────── */

    @Test
    void aCallerCanRefuseAStoredAnswer() {
        assertTrue(CachePolicy.callerRefusesReuse(headers("Cache-Control", "no-cache")));
        assertTrue(CachePolicy.callerRefusesReuse(headers("Cache-Control", "max-age=0")));
        assertFalse(CachePolicy.callerRefusesReuse(headers("Cache-Control", "max-age=60")));
        assertFalse(CachePolicy.callerRefusesReuse(new HttpHeaders()));
    }

    @Test
    void onlyNoStoreForbidsKeepingTheAnswer() {
        assertTrue(CachePolicy.callerRefusesStorage(headers("Cache-Control", "no-store")));
        assertFalse(CachePolicy.callerRefusesStorage(headers("Cache-Control", "no-cache")));
    }

    @Test
    void aCallerRunningItsOwnConditionalRequestIsLeftAlone() {
        assertTrue(CachePolicy.callerIsConditional(headers("If-None-Match", "\"v1\"")));
        assertTrue(CachePolicy.callerIsConditional(headers("Range", "bytes=0-10")));
        assertFalse(CachePolicy.callerIsConditional(headers("Accept", "application/json")));
    }

    /* ── Addressing ─────────────────────────────────────────────────────── */

    @Test
    void theCredentialIsPartOfTheAddress() {
        String one = CachePolicy.key(PROVIDER, CREDENTIAL, Identity.APP, "GET", route("/v1/orders"), new HttpHeaders());
        String other = CachePolicy.key(PROVIDER, UUID.randomUUID(), Identity.APP, "GET", route("/v1/orders"), new HttpHeaders());
        assertNotEquals(one, other);
    }

    @Test
    void theQueryAndTheNegotiatedTypeAreBothPartOfTheAddress() {
        var json = headers("Accept", "application/json");
        var xml = headers("Accept", "application/xml");
        var plain = GatewayPath.parse("/gateway/billing/v1/orders", "billing", null);
        var filtered = GatewayPath.parse("/gateway/billing/v1/orders", "billing", "status=open");
        assertNotEquals(
                CachePolicy.key(PROVIDER, CREDENTIAL, Identity.APP, "GET", plain, json),
                CachePolicy.key(PROVIDER, CREDENTIAL, Identity.APP, "GET", filtered, json));
        assertNotEquals(
                CachePolicy.key(PROVIDER, CREDENTIAL, Identity.APP, "GET", plain, json),
                CachePolicy.key(PROVIDER, CREDENTIAL, Identity.APP, "GET", plain, xml));
    }

    @Test
    void theMethodIsPartOfTheAddress() {
        assertNotEquals(
                CachePolicy.key(PROVIDER, CREDENTIAL, Identity.APP, "GET", route("/v1/orders"), new HttpHeaders()),
                CachePolicy.key(PROVIDER, CREDENTIAL, Identity.APP, "HEAD", route("/v1/orders"), new HttpHeaders()));
    }

    @Test
    void aWriteCoversTheResourceAndItsMembersButNotItsNeighbours() {
        String prefix = CachePolicy.resourcePrefix(PROVIDER, CREDENTIAL, Identity.APP);
        String collection = CachePolicy.key(PROVIDER, CREDENTIAL, Identity.APP, "GET", route("/v1/orders"), new HttpHeaders());
        String member = CachePolicy.key(PROVIDER, CREDENTIAL, Identity.APP, "GET", route("/v1/orders/42"), new HttpHeaders());
        String neighbour = CachePolicy.key(PROVIDER, CREDENTIAL, Identity.APP, "GET", route("/v1/orders-archive"), new HttpHeaders());

        assertTrue(CachePolicy.covers(collection, prefix, "/v1/orders"));
        assertTrue(CachePolicy.covers(member, prefix, "/v1/orders"));
        assertFalse(CachePolicy.covers(neighbour, prefix, "/v1/orders"));
        assertFalse(CachePolicy.covers(collection, prefix, "/v1/orders/42"));
    }

    @Test
    void anotherCredentialIsNeverCoveredByAWrite() {
        String otherPrefix = CachePolicy.resourcePrefix(PROVIDER, UUID.randomUUID(), Identity.APP);
        String mine = CachePolicy.key(PROVIDER, CREDENTIAL, Identity.APP, "GET", route("/v1/orders"), new HttpHeaders());
        assertFalse(CachePolicy.covers(mine, otherPrefix, "/v1/orders"));
    }

    /* ── Upstream signals ───────────────────────────────────────────────── */

    @Test
    void retryAfterIsUnderstoodInBothForms() {
        assertEquals(30, CachePolicy.retryAfterSeconds(headers("Retry-After", "30")));
        assertNull(CachePolicy.retryAfterSeconds(new HttpHeaders()));
        assertNull(CachePolicy.retryAfterSeconds(headers("Retry-After", "soon")));
        assertEquals(0, CachePolicy.retryAfterSeconds(headers("Retry-After", "Wed, 21 Oct 2015 07:28:00 GMT")));
    }

    @Test
    void anAgeAlreadySpentElsewhereIsCounted() {
        assertEquals(42, CachePolicy.upstreamAgeSeconds(headers("Age", "42")));
        assertEquals(0, CachePolicy.upstreamAgeSeconds(headers("Age", "-1")));
        assertEquals(0, CachePolicy.upstreamAgeSeconds(new HttpHeaders()));
    }

    @Test
    void varyNamesAreNormalised() {
        assertEquals(
                java.util.List.of("accept", "accept-language"),
                CachePolicy.varyNames(headers("Vary", "Accept, Accept-Language")));
    }
}
