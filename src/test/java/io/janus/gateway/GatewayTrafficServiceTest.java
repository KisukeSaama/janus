package io.janus.gateway;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import io.janus.accounts.Account;
import io.janus.applications.Application;
import io.janus.credentials.*;
import io.janus.gateway.transform.JsonNormalizer;
import io.janus.grants.Grant;
import io.janus.openbao.OpenBaoClient;
import io.janus.providers.Provider;
import io.janus.testing.Fixtures;

/**
 * The outbound half of the gateway: what it reuses, what it waits for, what it retries, and what it
 * refuses to let travel.
 *
 * <p>The store, the buckets and the cooldown are the real ones. Only the two things that reach
 * outside the process are stood in for — OpenBao and the network — because the behaviour under test
 * is precisely how those three collaborate, and mocking them would leave the tests asserting the
 * mocks' arrangement instead.
 */
class GatewayTrafficServiceTest {
    private static final String SECRET = "sk_live_31337";

    private final OpenBaoClient bao = Mockito.mock(OpenBaoClient.class);
    private final UpstreamTokenProvider tokens = Mockito.mock(UpstreamTokenProvider.class);

    private final GatewayTrafficProperties properties = new GatewayTrafficProperties(
            new GatewayTrafficProperties.Cache(true, 100, 1_000_000, 10_000_000, 300),
            // Deliberately impatient: a test should not spend two seconds discovering a refusal, and
            // a one-millisecond backoff exercises the same branch a realistic one does.
            new GatewayTrafficProperties.Throttle(1, 300),
            new GatewayTrafficProperties.Retry(2, 1, 1),
            new GatewayTrafficProperties.Authorization(true, 10, 100),
            new GatewayTrafficProperties.Transform(true, 2097152));

    private final ResponseCache cache = new ResponseCache(properties);
    private final RateLimiter limiter = new RateLimiter();
    private final UpstreamCooldown cooldown = new UpstreamCooldown();
    // The real one. It does nothing at all unless a destination asks for it, so every test that is
    // not about conversion is unaffected by its presence.
    private final JsonNormalizer normalizer = new JsonNormalizer(new ObjectMapper(), properties);
    private final IdentityMemory identities = new IdentityMemory();

    private final List<ClientRequest> sent = new ArrayList<>();
    private final Deque<Supplier<Mono<ClientResponse>>> answers = new ArrayDeque<>();

    private final Account owner = Fixtures.owner();
    private final Provider provider = Fixtures.provider(owner);
    private final Application application = Fixtures.application(owner);

    private GatewayTrafficService service;

    @BeforeEach
    void setUp() {
        var web = WebClient.builder()
                .exchangeFunction(request -> {
                    sent.add(request);
                    var next = answers.poll();
                    return next == null ? Mono.just(cacheable("{}")) : next.get();
                })
                .build();
        service =
                new GatewayTrafficService(
                        web, web, bao, tokens, cache, limiter, cooldown, normalizer, identities, properties, 30);
        when(bao.read(any())).thenReturn(SECRET);
    }

    // --- building the call under test ---------------------------------------

    private GatewayExchange exchange(Credential credential) {
        return exchange(HttpMethod.GET, "/v1/tracks", credential, new HttpHeaders());
    }

    private GatewayExchange exchange(HttpMethod method, String path, Credential credential, HttpHeaders headers) {
        var grant = Fixtures.grant(application, provider, credential);
        return exchange(method, path, grant, headers);
    }

    private GatewayExchange exchange(HttpMethod method, String path, Grant grant, HttpHeaders headers) {
        var route = GatewayPath.parse("/gateway/" + provider.getSlug() + path, provider.getSlug(), null);
        return new GatewayExchange(provider, grant, application.getId(), method, route, headers, null, "correlation-1");
    }

    private Credential bearer() {
        return Fixtures.credential(provider);
    }

    /** A response the store is allowed to keep, so a second call can be answered from it. */
    private static ClientResponse cacheable(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.CACHE_CONTROL, "max-age=60")
                .body(body)
                .build();
    }

    private static ClientResponse status(HttpStatus status, String... headers) {
        var builder = ClientResponse.create(status);
        for (int i = 0; i < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
        return builder.body("").build();
    }

    private void willAnswer(ClientResponse... responses) {
        for (var response : responses) answers.add(() -> Mono.just(response));
    }

    private void willFailToConnect() {
        answers.add(() -> Mono.error(new IllegalStateException("Connection refused")));
    }

    private static String header(ClientRequest request, String name) {
        return request.headers().getFirst(name);
    }

    // --- how a credential is presented --------------------------------------

    @Test
    void readsTheSecretOnlyWhenARequestActuallyHasToLeave() {
        service.forward(exchange(bearer()));

        verify(bao).read(any());
        assertThat(header(sent.getFirst(), HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + SECRET);
    }

    @Test
    void presentsAnApiKeyInTheHeaderTheProviderExpects() {
        var credential = new Credential(
                provider,
                "key",
                new Credential.Strategy(AuthType.API_KEY_HEADER, "X-Api-Key", null, null, null, null),
                null,
                true);

        service.forward(exchange(credential));

        assertThat(header(sent.getFirst(), "X-Api-Key")).isEqualTo(SECRET);
        assertThat(header(sent.getFirst(), HttpHeaders.AUTHORIZATION)).isNull();
    }

    @Test
    void presentsAnApiKeyInTheQueryStringWhenThatIsWhereItGoes() {
        var credential = new Credential(
                provider,
                "key",
                new Credential.Strategy(AuthType.API_KEY_QUERY, null, "apikey", null, null, null),
                null,
                true);

        service.forward(exchange(credential));

        assertThat(sent.getFirst().url().toString()).contains("apikey=" + SECRET);
        assertThat(header(sent.getFirst(), HttpHeaders.AUTHORIZATION)).isNull();
    }

    @Test
    void encodesABasicCredentialTheWayTheStandardRequires() {
        when(bao.read(any())).thenReturn("alice:hunter2");
        var credential = Fixtures.credential(provider, AuthType.BASIC);

        service.forward(exchange(credential));

        String expected = "Basic "
                + Base64.getEncoder().encodeToString("alice:hunter2".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(header(sent.getFirst(), HttpHeaders.AUTHORIZATION)).isEqualTo(expected);
    }

    /** The stored value is the means of obtaining a token; it is never what travels. */
    @Test
    void sendsTheObtainedTokenRatherThanTheStoredClientCredentials() {
        when(bao.read(any())).thenReturn("client-id:client-secret");
        when(tokens.tokenFor(any(), any(), eq("client-id:client-secret"))).thenReturn("short-lived-token");
        var credential = new Credential(
                provider,
                "key",
                new Credential.Strategy(
                        AuthType.OAUTH2_CLIENT_CREDENTIALS,
                        null,
                        null,
                        "https://auth.example.com/token",
                        null,
                        TokenClientAuth.BASIC),
                null,
                true);

        service.forward(exchange(credential));

        assertThat(header(sent.getFirst(), HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer short-lived-token");
        assertThat(sent.getFirst().headers().toString()).doesNotContain("client-secret");
    }

    // --- reuse ---------------------------------------------------------------

    /**
     * The strongest guarantee the store offers is not that it is fast: it is that a served hit never
     * caused a secret to be read, so nothing left OpenBao and nothing left the process.
     */
    @Test
    void aStoredAnswerIsServedWithoutReadingTheSecretAgain() {
        var credential = bearer();
        service.forward(exchange(credential));
        clearInvocations(bao);

        var second = service.forward(exchange(credential));

        assertThat(second.cacheStatus()).isEqualTo(CacheStatus.HIT);
        assertThat(second.headers().getFirst(GatewayTrafficService.CACHE_HEADER))
                .isEqualTo("HIT");
        assertThat(second.headers().getFirst(HttpHeaders.AGE)).isNotNull();
        verifyNoInteractions(bao);
        assertThat(sent).hasSize(1);
    }

    /**
     * The catalogue is shared, so two accounts calling the same API at the same path are the ordinary
     * case, not the exotic one. What separates them is the credential the call would have been made
     * with: it is part of the address, so neither account can be answered from what the other fetched
     * — and the second call is a miss even though every visible part of the request is identical.
     */
    @Test
    void neverAnswersOneAccountFromWhatAnotherAccountFetched() {
        var otherOwner = Fixtures.owner();
        var otherApplication = Fixtures.application(otherOwner);
        var theirCredential =
                new Credential(otherOwner.getId(), provider, "theirs", Credential.strategyOf(provider), null, true);
        var theirGrant = Fixtures.grant(otherApplication, provider, theirCredential);

        service.forward(exchange(bearer()));
        var theirs = service.forward(exchange(HttpMethod.GET, "/v1/tracks", theirGrant, new HttpHeaders()));

        assertThat(theirs.cacheStatus()).isEqualTo(CacheStatus.MISS);
        assertThat(sent).hasSize(2);
    }

    /** The other half of the same rule: what one account rotates never empties another's entries. */
    @Test
    void droppingOneAccountsEntriesLeavesAnotherAccountsAlone() {
        var credential = bearer();
        var otherCredential = Fixtures.credential(provider);
        service.forward(exchange(credential));
        service.forward(exchange(otherCredential));

        cache.invalidateCredential(otherCredential.getId());
        var mine = service.forward(exchange(credential));

        assertThat(mine.cacheStatus()).isEqualTo(CacheStatus.HIT);
    }

    @Test
    void aCallerAskingForAFreshCopyGetsOne() {
        var credential = bearer();
        service.forward(exchange(credential));

        var headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, "no-cache");
        var second = service.forward(exchange(HttpMethod.GET, "/v1/tracks", credential, headers));

        assertThat(second.cacheStatus()).isEqualTo(CacheStatus.MISS);
        assertThat(sent).hasSize(2);
    }

    @Test
    void anUnsafeMethodIsNeverAnsweredFromTheStore() {
        var credential = bearer();

        var outcome = service.forward(exchange(HttpMethod.POST, "/v1/tracks", credential, new HttpHeaders()));

        assertThat(outcome.cacheStatus()).isEqualTo(CacheStatus.BYPASS);
    }

    /** A write makes what was read about that resource questionable, including what lives under it. */
    @Test
    void aWriteDropsWhatWasStoredAboutTheResource() {
        var credential = bearer();
        service.forward(exchange(HttpMethod.GET, "/v1/tracks", credential, new HttpHeaders()));

        service.forward(exchange(HttpMethod.POST, "/v1/tracks", credential, new HttpHeaders()));
        var afterWrite = service.forward(exchange(HttpMethod.GET, "/v1/tracks", credential, new HttpHeaders()));

        assertThat(afterWrite.cacheStatus()).isEqualTo(CacheStatus.MISS);
    }

    @Test
    void storesNothingForAProviderThatDoesNotAllowIt() {
        provider.applyTrafficPolicy(new Provider.TrafficPolicy(false, 0, 0, 0));
        var credential = bearer();

        service.forward(exchange(credential));
        var second = service.forward(exchange(credential));

        assertThat(second.cacheStatus()).isEqualTo(CacheStatus.BYPASS);
        assertThat(sent).hasSize(2);
    }

    /**
     * The entry carries an ETag and no Last-Modified, which is the ordinary shape of a JSON API's
     * response and the case where carrying both validators over used to require both to exist.
     */
    @Test
    void aConfirmedStoredAnswerIsReturnedWithoutItsBodyBeingSentAgain() {
        var credential = bearer();
        willAnswer(
                ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CACHE_CONTROL, "max-age=0")
                        .header(HttpHeaders.ETAG, "\"v1\"")
                        .body("original body")
                        .build(),
                status(HttpStatus.NOT_MODIFIED));

        service.forward(exchange(credential));
        var revalidated = service.forward(exchange(credential));

        assertThat(revalidated.cacheStatus()).isEqualTo(CacheStatus.REVALIDATED);
        assertThat(new String(revalidated.body())).isEqualTo("original body");
        assertThat(header(sent.get(1), HttpHeaders.IF_NONE_MATCH)).isEqualTo("\"v1\"");
    }

    // --- what the caller is told it may reuse --------------------------------

    /**
     * The provider states a default TTL and the upstream states nothing. Janus reuses the answer on
     * that policy, so it says so: without a {@code Cache-Control} of its own the response carries the
     * platform's {@code no-store}, and Janus would be the only party in the chain able to act on a
     * policy the operator set for everyone.
     */
    @Test
    void announcesTheFreshnessAProviderPolicyGrantedWhenTheUpstreamStatedNone() {
        provider.applyTrafficPolicy(new Provider.TrafficPolicy(true, 120, 0, 0));
        willAnswer(status(HttpStatus.OK));

        var outcome = service.forward(exchange(bearer()));

        assertThat(outcome.headers().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("private, max-age=120");
    }

    /**
     * Always {@code private}: one hop out, nothing knows these answers are addressed by credential,
     * and a shared cache keyed on the URL alone would hand one account's answer to another's request.
     */
    @Test
    void whatItAnnouncesIsNeverReusableByAnybodyInBetween() {
        provider.applyTrafficPolicy(new Provider.TrafficPolicy(true, 120, 0, 0));
        willAnswer(status(HttpStatus.OK));

        var outcome = service.forward(exchange(bearer()));

        assertThat(outcome.headers().getFirst(HttpHeaders.CACHE_CONTROL)).startsWith("private");
    }

    /** An upstream that stated its own policy is the party that knows; it is not restated. */
    @Test
    void leavesAnUpstreamsOwnFreshnessPolicyAlone() {
        provider.applyTrafficPolicy(new Provider.TrafficPolicy(true, 120, 0, 0));

        var outcome = service.forward(exchange(bearer()));

        assertThat(outcome.headers().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("max-age=60");
    }

    /**
     * A hit announces the lifetime the entry was stored with, not what is left of it: the caller
     * subtracts {@code Age} itself, and announcing the remainder would have it counted twice.
     */
    @Test
    void aHitAnnouncesTheWholeLifetimeAlongsideItsAge() {
        provider.applyTrafficPolicy(new Provider.TrafficPolicy(true, 120, 0, 0));
        willAnswer(status(HttpStatus.OK), status(HttpStatus.OK));
        var credential = bearer();

        service.forward(exchange(credential));
        var second = service.forward(exchange(credential));

        assertThat(second.cacheStatus()).isEqualTo(CacheStatus.HIT);
        assertThat(second.headers().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("private, max-age=120");
        assertThat(second.headers().getFirst(HttpHeaders.AGE)).isNotNull();
    }

    /** A stale answer is served because the alternative was an error. It is not one to keep. */
    @Test
    void announcesNoFreshnessForAnAnswerServedOnlyBecauseTheUpstreamWasFailing() {
        var credential = bearer();
        willAnswer(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CACHE_CONTROL, "max-age=0")
                .body("body")
                .build());
        service.forward(exchange(credential));
        // One first attempt plus the two retries an idempotent method is allowed.
        willFailToConnect();
        willFailToConnect();
        willFailToConnect();

        var stale = service.forward(exchange(credential));

        assertThat(stale.cacheStatus()).isEqualTo(CacheStatus.STALE);
        assertThat(stale.headers().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("max-age=0");
    }

    // --- a caller running its own condition -----------------------------------

    /** The client that keeps an ETag was the one caller the store never helped. Now it does. */
    @Test
    void answersACallersOwnIfNoneMatchFromTheStore() {
        var credential = bearer();
        willAnswer(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CACHE_CONTROL, "max-age=60")
                .header(HttpHeaders.ETAG, "\"v1\"")
                .body("original body")
                .build());
        service.forward(exchange(credential));

        var headers = new HttpHeaders();
        headers.set(HttpHeaders.IF_NONE_MATCH, "\"v1\"");
        var conditional = service.forward(exchange(HttpMethod.GET, "/v1/tracks", credential, headers));

        assertThat(conditional.status()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(conditional.body()).isEmpty();
        assertThat(sent).hasSize(1);
    }

    /** The weak comparison function: {@code W/"v1"} and {@code "v1"} name the same representation. */
    @Test
    void comparesTagsWeaklyAsTheStandardRequiresForThisCondition() {
        var credential = bearer();
        willAnswer(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CACHE_CONTROL, "max-age=60")
                .header(HttpHeaders.ETAG, "\"v1\"")
                .body("original body")
                .build());
        service.forward(exchange(credential));

        var headers = new HttpHeaders();
        headers.set(HttpHeaders.IF_NONE_MATCH, "W/\"v0\", W/\"v1\"");
        var conditional = service.forward(exchange(HttpMethod.GET, "/v1/tracks", credential, headers));

        assertThat(conditional.status()).isEqualTo(HttpStatus.NOT_MODIFIED);
    }

    /** A caller holding an older tag gets the representation Janus holds, not a round trip. */
    @Test
    void servesTheStoredRepresentationWhenTheCallersTagIsNoLongerTheCurrentOne() {
        var credential = bearer();
        willAnswer(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CACHE_CONTROL, "max-age=60")
                .header(HttpHeaders.ETAG, "\"v2\"")
                .body("current body")
                .build());
        service.forward(exchange(credential));

        var headers = new HttpHeaders();
        headers.set(HttpHeaders.IF_NONE_MATCH, "\"v1\"");
        var conditional = service.forward(exchange(HttpMethod.GET, "/v1/tracks", credential, headers));

        assertThat(conditional.status()).isEqualTo(HttpStatus.OK);
        assertThat(new String(conditional.body())).isEqualTo("current body");
        assertThat(sent).hasSize(1);
    }

    /** Nothing fresh to answer with: the caller's exchange goes out as its own, unmixed. */
    @Test
    void forwardsAConditionalCallUntouchedWhenNothingFreshCanAnswerIt() {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.IF_NONE_MATCH, "\"v1\"");

        var outcome = service.forward(exchange(HttpMethod.GET, "/v1/tracks", bearer(), headers));

        assertThat(outcome.cacheStatus()).isEqualTo(CacheStatus.BYPASS);
        assertThat(header(sent.getFirst(), HttpHeaders.IF_NONE_MATCH)).isEqualTo("\"v1\"");
    }

    /** Every other condition still belongs to the caller alone, hit or no hit. */
    @Test
    void neverAnswersARangeOrADateConditionFromTheStore() {
        var credential = bearer();
        service.forward(exchange(credential));

        var headers = new HttpHeaders();
        headers.set(HttpHeaders.RANGE, "bytes=0-99");
        var ranged = service.forward(exchange(HttpMethod.GET, "/v1/tracks", credential, headers));

        assertThat(ranged.cacheStatus()).isEqualTo(CacheStatus.BYPASS);
        assertThat(sent).hasSize(2);
    }

    // --- allowances ----------------------------------------------------------

    @Test
    void refusesACallerThatHasSpentItsOwnAllowance() {
        var credential = bearer();
        var grant = Fixtures.grant(application, provider, credential);
        grant.applyQuota(new Grant.Quota(1, 1));

        service.forward(exchange(HttpMethod.GET, "/v1/one", grant, new HttpHeaders()));
        var refusal =
                catchThrowable(() -> service.forward(exchange(HttpMethod.GET, "/v1/two", grant, new HttpHeaders())));

        assertThat(refusal).isInstanceOf(Throttled.class);
        var throttled = (Throttled) refusal;
        assertThat(throttled.retryAfterSeconds).isPositive();
        assertThat(throttled.headers.getFirst(GatewayTrafficService.LIMIT_HEADER))
                .isEqualTo("1");
    }

    /** The caller's own standing is reported even when the refusal was owed to the provider. */
    @Test
    void aProviderRefusalStillTellsTheCallerWhereItStands() {
        provider.applyTrafficPolicy(new Provider.TrafficPolicy(true, 0, 1, 1));
        var credential = bearer();
        var grant = Fixtures.grant(application, provider, credential);
        grant.applyQuota(new Grant.Quota(100, 100));

        service.forward(exchange(HttpMethod.GET, "/v1/one", grant, new HttpHeaders()));
        var refusal =
                catchThrowable(() -> service.forward(exchange(HttpMethod.GET, "/v1/two", grant, new HttpHeaders())));

        assertThat(refusal).isInstanceOf(Throttled.class);
        assertThat(((Throttled) refusal).headers.getFirst(GatewayTrafficService.LIMIT_HEADER))
                .isEqualTo("100");
    }

    // --- failing upstreams ---------------------------------------------------

    @Test
    void retriesAnIdempotentCallThatTheProviderCouldNotServe() {
        willAnswer(status(HttpStatus.SERVICE_UNAVAILABLE), cacheable("{}"));

        var outcome = service.forward(exchange(bearer()));

        assertThat(outcome.status()).isEqualTo(HttpStatus.OK);
        assertThat(outcome.headers().getFirst(GatewayTrafficService.ATTEMPTS_HEADER))
                .isEqualTo("2");
        assertThat(sent).hasSize(2);
    }

    /** A second POST could create a second thing; no retry can be safe without knowing the API. */
    @Test
    void doesNotRetryACallThatCouldHappenTwice() {
        willAnswer(status(HttpStatus.SERVICE_UNAVAILABLE));

        var outcome = service.forward(exchange(HttpMethod.POST, "/v1/tracks", bearer(), new HttpHeaders()));

        assertThat(outcome.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(sent).hasSize(1);
    }

    /**
     * A pause longer than a retry could absorb is a rate limit, not a hiccup. Honouring it for
     * everybody is what keeps a short refusal from turning into a long ban.
     */
    @Test
    void holdsTheDoorForEverybodyWhenAProviderAsksForALongPause() {
        willAnswer(status(HttpStatus.TOO_MANY_REQUESTS, HttpHeaders.RETRY_AFTER, "120"));
        var credential = bearer();

        service.forward(exchange(HttpMethod.GET, "/v1/one", credential, new HttpHeaders()));
        int sentBefore = sent.size();
        var refusal = catchThrowable(
                () -> service.forward(exchange(HttpMethod.GET, "/v1/two", credential, new HttpHeaders())));

        assertThat(refusal).isInstanceOf(Throttled.class);
        assertThat(sent).hasSize(sentBefore);
        assertThat(cooldown.active()).hasSize(1);
    }

    @Test
    void servesAnExpiredAnswerRatherThanAnErrorWhenTheProviderIsUnreachable() {
        var credential = bearer();
        willAnswer(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CACHE_CONTROL, "max-age=0, stale-if-error=300")
                .body("last known good")
                .build());
        service.forward(exchange(credential));

        // One first attempt plus the two retries an idempotent method is allowed.
        willFailToConnect();
        willFailToConnect();
        willFailToConnect();
        var outcome = service.forward(exchange(credential));

        assertThat(outcome.cacheStatus()).isEqualTo(CacheStatus.STALE);
        assertThat(new String(outcome.body())).isEqualTo("last known good");
        assertThat(outcome.auditDetail()).contains("upstream unreachable");
    }

    @Test
    void raisesTheFailureWhenThereIsNoStoredAnswerToFallBackOn() {
        willFailToConnect();
        willFailToConnect();
        willFailToConnect();

        assertThatThrownBy(() -> service.forward(exchange(bearer()))).isInstanceOf(IllegalStateException.class);
    }

    // --- what must not travel back ------------------------------------------

    /** A provider must not be able to set a cookie or issue a challenge in the caller's context. */
    @Test
    void doesNotReturnTheProvidersSessionOrAuthenticationHeaders() {
        willAnswer(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.SET_COOKIE, "session=abc")
                .header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"provider\"")
                .header("X-Request-Id", "upstream-1")
                .body("{}")
                .build());

        var outcome = service.forward(exchange(bearer()));

        assertThat(outcome.headers().headerNames())
                .doesNotContain(HttpHeaders.SET_COOKIE, HttpHeaders.WWW_AUTHENTICATE);
        assertThat(outcome.headers().getFirst("X-Request-Id")).isEqualTo("upstream-1");
    }

    /** An upstream that rejects a key routinely quotes it back in the refusal it returns. */
    @Test
    void scrubsTheCredentialOutOfAnythingTheProviderEchoesBack() {
        willAnswer(ClientResponse.create(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body("{\"error\":\"invalid key " + SECRET + "\"}")
                .build());

        var outcome = service.forward(exchange(HttpMethod.POST, "/v1/tracks", bearer(), new HttpHeaders()));

        assertThat(new String(outcome.body())).doesNotContain(SECRET);
    }

    // --- restating the answer as JSON ---------------------------------------

    /** A Plex library, in the shape Plex actually returns it. */
    private static ClientResponse library() {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_XML_VALUE)
                .header(HttpHeaders.ETAG, "\"v1\"")
                .header(HttpHeaders.CACHE_CONTROL, "max-age=60")
                .body("<MediaContainer size=\"1\"><Directory key=\"4\"/></MediaContainer>")
                .build();
    }

    private static String body(GatewayOutcome outcome) {
        return new String(outcome.body(), StandardCharsets.UTF_8);
    }

    private void normalising(String arrayPaths) {
        provider.applyNormalization(new Provider.Normalization(true, arrayPaths));
    }

    @Test
    void restatesAnXmlAnswerAsJsonWhereTheDestinationAsksForIt() {
        normalising("Directory");
        willAnswer(library());

        var outcome = service.forward(exchange(bearer()));

        assertThat(body(outcome)).isEqualTo("{\"MediaContainer\":{\"@size\":\"1\",\"Directory\":[{\"@key\":\"4\"}]}}");
        assertThat(outcome.headers().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(outcome.headers().getFirst(JsonNormalizer.TRANSFORM_HEADER)).isEqualTo("xml->json");
    }

    @Test
    void leavesTheAnswerAloneWhereTheDestinationDoesNotAskForIt() {
        willAnswer(library());

        var outcome = service.forward(exchange(bearer()));

        assertThat(body(outcome)).startsWith("<MediaContainer");
        assertThat(outcome.headers().headerNames()).doesNotContain(JsonNormalizer.TRANSFORM_HEADER);
        assertThat(outcome.headers().getVary()).isEmpty();
    }

    /**
     * The upstream issued its validator for the document it sent. A caller holding it for one it
     * never received would revalidate against the wrong representation, so it does not travel.
     */
    @Test
    void dropsTheUpstreamValidatorOnceTheBodyIsNoLongerTheOneItDescribes() {
        normalising(null);
        willAnswer(library());

        var outcome = service.forward(exchange(bearer()));

        assertThat(outcome.headers().getETag()).isNull();
        assertThat(outcome.headers().getVary()).contains(HttpHeaders.ACCEPT);
    }

    /**
     * The store holds what the upstream sent, not what the caller received, so the conversion runs
     * again on the way out of a hit — and the same entry could still answer a caller that wanted the
     * original.
     */
    @Test
    void storesTheOriginalAndConvertsOnTheWayOut() {
        normalising("Directory");
        willAnswer(library());
        // The same credential both times: a stored entry is addressed by the one that fetched it.
        var credential = bearer();

        service.forward(exchange(credential));
        var second = service.forward(exchange(credential));

        assertThat(second.cacheStatus()).isEqualTo(CacheStatus.HIT);
        assertThat(sent).hasSize(1);
        assertThat(body(second)).isEqualTo("{\"MediaContainer\":{\"@size\":\"1\",\"Directory\":[{\"@key\":\"4\"}]}}");
    }

    /** Asking for the original is how a caller opts out, one request at a time. */
    @Test
    void returnsTheOriginalToACallerThatNamedIt() {
        normalising(null);
        willAnswer(library());
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_XML_VALUE);

        var outcome = service.forward(exchange(HttpMethod.GET, "/v1/library", bearer(), headers));

        assertThat(body(outcome)).startsWith("<MediaContainer");
        // Still stated: the answer depends on Accept whether or not this caller exercised it.
        assertThat(outcome.headers().getVary()).contains(HttpHeaders.ACCEPT);
    }

    /** A conversion is never a way for a request to fail; the reason travels instead of an error. */
    @Test
    void returnsTheUpstreamsOwnBytesWhenTheyAreNotWhatTheyClaimed() {
        normalising(null);
        willAnswer(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_XML_VALUE)
                .body("<MediaContainer><Directory></MediaContainer>")
                .build());

        var outcome = service.forward(exchange(bearer()));

        assertThat(outcome.status().value()).isEqualTo(200);
        assertThat(body(outcome)).isEqualTo("<MediaContainer><Directory></MediaContainer>");
        assertThat(outcome.headers().getFirst(JsonNormalizer.TRANSFORM_HEADER)).startsWith("none (");
    }

    // --- speaking for the application, or for whoever connected their account ------------------

    /**
     * One API, two identities, and nothing in a URL that says which an endpoint wants. What is
     * asserted here is the whole bargain: the application goes first because its answers are the ones
     * that can be shared, a refusal is investigated rather than believed, and the lesson is only drawn
     * when the other identity actually works.
     */
    @Nested
    class Identities {

        private Credential connected() {
            var connection = new Provider.Connection(
                    "https://accounts.example.com/authorize",
                    "https://accounts.example.com/token",
                    null,
                    TokenClientAuth.BASIC);
            provider.applyConnection(connection);
            var credential = Fixtures.credential(provider, AuthType.OAUTH2_CLIENT_CREDENTIALS);
            credential.applyConnection(connection);
            credential.authorized("someone@example.com");
            when(tokens.tokenFor(any(), eq(Identity.APP), any())).thenReturn("the-applications-token");
            when(tokens.tokenFor(any(), eq(Identity.ACCOUNT), any())).thenReturn("somebodys-own-token");
            return credential;
        }

        private String presented(int index) {
            return sent.get(index).headers().getFirst(HttpHeaders.AUTHORIZATION);
        }

        @Test
        void speaksForTheApplicationWhenNothingIsKnownAboutTheEndpoint() {
            var outcome = service.forward(exchange(connected()));

            assertThat(outcome.headers().getFirst(GatewayTrafficService.IDENTITY_HEADER))
                    .isEqualTo("app");
            assertThat(presented(0)).isEqualTo("Bearer the-applications-token");
        }

        /**
         * The sequence that matters. A refusal is ambiguous, so the held token is dropped and the same
         * identity asked again; only when that is refused too is the other one tried.
         */
        @Test
        void triesTheConnectedAccountOnlyAfterRulingOutAnExpiredToken() {
            var credential = connected();
            willAnswer(status(HttpStatus.UNAUTHORIZED), status(HttpStatus.UNAUTHORIZED), cacheable("{\"mine\":1}"));

            var outcome = service.forward(exchange(HttpMethod.GET, "/v1/me/playlists", credential, new HttpHeaders()));

            assertThat(outcome.status().value()).isEqualTo(200);
            assertThat(outcome.headers().getFirst(GatewayTrafficService.IDENTITY_HEADER))
                    .isEqualTo("account");
            verify(tokens).invalidate(credential.getId(), Identity.APP);
            assertThat(sent).hasSize(3);
            assertThat(presented(0)).isEqualTo("Bearer the-applications-token");
            assertThat(presented(1)).isEqualTo("Bearer the-applications-token");
            assertThat(presented(2)).isEqualTo("Bearer somebodys-own-token");
        }

        /**
         * Without the second attempt above, a token that merely aged out would teach Janus that the
         * endpoint belongs to somebody else — and it would go on sending every later call there.
         */
        @Test
        void anExpiredTokenTeachesNothing() {
            var credential = connected();
            willAnswer(status(HttpStatus.UNAUTHORIZED), cacheable("{\"mine\":1}"));

            var outcome = service.forward(exchange(HttpMethod.GET, "/v1/tracks", credential, new HttpHeaders()));

            assertThat(outcome.headers().getFirst(GatewayTrafficService.IDENTITY_HEADER))
                    .isEqualTo("app");
            assertThat(identities.recall(credential.getId(), "GET", "/v1/tracks")).isEmpty();
            assertThat(sent).hasSize(2);
        }

        @Test
        void remembersTheEndpointSoTheNextCallGoesOutRightTheFirstTime() {
            var credential = connected();
            willAnswer(
                    status(HttpStatus.UNAUTHORIZED),
                    status(HttpStatus.UNAUTHORIZED),
                    cacheable("{\"mine\":1}"),
                    cacheable("{\"mine\":2}"));

            service.forward(exchange(HttpMethod.GET, "/v1/me/playlists", credential, noStore()));
            sent.clear();
            var second =
                    service.forward(exchange(HttpMethod.GET, "/v1/me/playlists", credential, noStore()));

            assertThat(second.headers().getFirst(GatewayTrafficService.IDENTITY_HEADER))
                    .isEqualTo("account");
            assertThat(sent).hasSize(1);
            assertThat(presented(0)).isEqualTo("Bearer somebodys-own-token");
        }

        /** Two refusals are a fact about the credential, not about which identity to present. */
        @Test
        void learnsNothingWhenNeitherIdentityIsAccepted() {
            var credential = connected();
            willAnswer(
                    status(HttpStatus.UNAUTHORIZED), status(HttpStatus.UNAUTHORIZED), status(HttpStatus.UNAUTHORIZED));

            var outcome = service.forward(exchange(HttpMethod.GET, "/v1/me/playlists", credential, new HttpHeaders()));

            assertThat(outcome.status().value()).isEqualTo(401);
            // The one that was meant, not the one that was tried last.
            assertThat(outcome.headers().getFirst(GatewayTrafficService.IDENTITY_HEADER))
                    .isEqualTo("app");
            assertThat(identities.recall(credential.getId(), "GET", "/v1/me/playlists"))
                    .isEmpty();
        }

        @Test
        void aCallerThatNamesAnIdentityGetsItWithoutAnyReplay() {
            var credential = connected();
            willAnswer(status(HttpStatus.UNAUTHORIZED));
            var grant = Fixtures.grant(application, provider, credential);
            var route = GatewayPath.parse("/gateway/" + provider.getSlug() + "/v1/tracks", provider.getSlug(), null);
            var pinned = new GatewayExchange(
                    provider,
                    grant,
                    application.getId(),
                    HttpMethod.GET,
                    route,
                    new HttpHeaders(),
                    null,
                    "correlation-1",
                    Identity.ACCOUNT);

            var outcome = service.forward(pinned);

            assertThat(outcome.status().value()).isEqualTo(401);
            assertThat(sent).hasSize(1);
            assertThat(presented(0)).isEqualTo("Bearer somebodys-own-token");
        }

        /**
         * The line between a convenience and a duplicated write. A 403 means understood and refused,
         * which an API may perfectly well say after having acted on part of a write. So a POST that met
         * one is handed back as it came, and the journal says why it was not repaired.
         */
        @Test
        void doesNotReplayAWriteThatMayAlreadyHaveTakenEffect() {
            var credential = connected();
            willAnswer(status(HttpStatus.FORBIDDEN));

            var outcome =
                    service.forward(exchange(HttpMethod.POST, "/v1/me/playlists", credential, new HttpHeaders()));

            assertThat(outcome.status().value()).isEqualTo(403);
            assertThat(sent).hasSize(1);
            assertThat(outcome.auditDetail()).contains("identity not replayed");
            assertThat(identities.recall(credential.getId(), "POST", "/v1/me/playlists"))
                    .isEmpty();
        }

        /** A 401 refuses the request before it is acted on, so the same write may be tried again. */
        @Test
        void replaysAWriteThatWasNeverAdmitted() {
            var credential = connected();
            willAnswer(status(HttpStatus.UNAUTHORIZED), status(HttpStatus.UNAUTHORIZED), cacheable("{\"id\":1}"));

            var outcome =
                    service.forward(exchange(HttpMethod.POST, "/v1/me/playlists", credential, new HttpHeaders()));

            assertThat(outcome.status().value()).isEqualTo(200);
            assertThat(outcome.headers().getFirst(GatewayTrafficService.IDENTITY_HEADER))
                    .isEqualTo("account");
            assertThat(sent).hasSize(3);
        }

        /** A second identical DELETE leaves the same state behind, so a 403 is worth investigating. */
        @Test
        void replaysAnIdempotentWriteWhateverTheRefusalWas() {
            var credential = connected();
            willAnswer(status(HttpStatus.FORBIDDEN), status(HttpStatus.FORBIDDEN), status(HttpStatus.NO_CONTENT));

            var outcome = service.forward(
                    exchange(HttpMethod.DELETE, "/v1/me/playlists/3cEYpjA9oz9GiPac4AsH4n", credential, new HttpHeaders()));

            assertThat(outcome.status().value()).isEqualTo(204);
            assertThat(sent).hasSize(3);
        }

        /** Nobody has agreed, so there is nothing to fall back to and nothing to investigate. */
        @Test
        void doesNotReachForAnAccountNobodyHasConnected() {
            var credential = connected();
            credential.forgetAuthorization();
            willAnswer(status(HttpStatus.UNAUTHORIZED), status(HttpStatus.UNAUTHORIZED));

            var outcome = service.forward(exchange(HttpMethod.GET, "/v1/me/playlists", credential, new HttpHeaders()));

            assertThat(outcome.status().value()).isEqualTo(401);
            assertThat(sent).hasSize(2);
        }

        /**
         * The store is addressed by identity, and this is the line that keeps one person's data out of
         * everybody else's answers: an entry fetched as the account must not answer a call made as the
         * application.
         */
        @Test
        void whatWasFetchedForOnePersonIsNotServedToTheApplication() {
            var credential = connected();
            willAnswer(
                    status(HttpStatus.UNAUTHORIZED),
                    status(HttpStatus.UNAUTHORIZED),
                    cacheable("{\"private\":\"theirs\"}"),
                    cacheable("{\"public\":\"everyones\"}"));

            service.forward(exchange(HttpMethod.GET, "/v1/me/playlists", credential, new HttpHeaders()));
            // Same path, asked for as the application. Were the store blind to identity, this would be
            // answered from the entry above without a single byte leaving the process.
            var grant = Fixtures.grant(application, provider, credential);
            var route = GatewayPath.parse(
                    "/gateway/" + provider.getSlug() + "/v1/me/playlists", provider.getSlug(), null);
            var asApp = new GatewayExchange(
                    provider,
                    grant,
                    application.getId(),
                    HttpMethod.GET,
                    route,
                    new HttpHeaders(),
                    null,
                    "correlation-2",
                    Identity.APP);

            var outcome = service.forward(asApp);

            assertThat(outcome.cacheStatus()).isNotEqualTo(CacheStatus.HIT);
            assertThat(body(outcome)).isEqualTo("{\"public\":\"everyones\"}");
        }

        private static HttpHeaders noStore() {
            var headers = new HttpHeaders();
            headers.set(HttpHeaders.CACHE_CONTROL, "no-store");
            return headers;
        }
    }
}
