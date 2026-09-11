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

import io.janus.applications.Application;
import io.janus.credentials.*;
import io.janus.gateway.graphql.*;
import io.janus.gateway.transform.JsonNormalizer;
import io.janus.grants.Grant;
import io.janus.openbao.OpenBaoClient;
import io.janus.providers.Provider;
import io.janus.testing.Fixtures;

/**
 * The outbound half, for GraphQL: a query is a POST that reads, so everything HTTP reserves for a GET
 * (reuse, sharing between identical calls, a second attempt) applies to it, and none of it applies to
 * a mutation.
 */
class GatewayTrafficGraphQlTest {
    private final OpenBaoClient bao = Mockito.mock(OpenBaoClient.class);
    private final UpstreamTokenProvider tokens = Mockito.mock(UpstreamTokenProvider.class);
    private final GatewayTrafficProperties properties = new GatewayTrafficProperties(
            new GatewayTrafficProperties.Cache(true, 100, 1_000_000, 10_000_000, 300),
            new GatewayTrafficProperties.Throttle(1, 300),
            new GatewayTrafficProperties.Retry(2, 1, 1),
            new GatewayTrafficProperties.Authorization(true, 10, 100),
            new GatewayTrafficProperties.Transform(true, 2097152));

    private final List<ClientRequest> sent = new ArrayList<>();
    private final Deque<Supplier<Mono<ClientResponse>>> answers = new ArrayDeque<>();

    private final io.janus.accounts.Account owner = Fixtures.owner();
    private final Provider provider = Fixtures.provider(owner, "github");
    private final Application application = Fixtures.application(owner);
    private final Credential credential = Fixtures.credential(provider);
    private final Grant grant = Fixtures.grant(application, provider, credential);

    private GatewayTrafficService service;

    @BeforeEach
    void setUp() {
        provider.applyGraphQl(new Provider.GraphQl("/graphql", 0, 0));
        var web = WebClient.builder()
                .exchangeFunction(request -> {
                    sent.add(request);
                    var next = answers.poll();
                    return next == null ? Mono.just(answer("{\"data\":{}}")) : next.get();
                })
                .build();
        service = new GatewayTrafficService(
                web,
                web,
                bao,
                tokens,
                new ResponseCache(properties),
                new RateLimiter(),
                new UpstreamCooldown(),
                new JsonNormalizer(new ObjectMapper(), properties),
                new IdentityMemory(),
                properties,
                30);
        when(bao.read(any())).thenReturn("sk_live_31337");
    }

    private static ClientResponse answer(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.CACHE_CONTROL, "max-age=60")
                .body(body)
                .build();
    }

    private void willAnswer(ClientResponse... responses) {
        for (var response : responses) answers.add(() -> Mono.just(response));
    }

    private GatewayExchange call(OperationType type, String body) {
        var route = GatewayPath.parse("/gateway/github/graphql", "github", null);
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var operation = GraphQlCall.of(new GraphQlOperation(type, null, Set.of("viewer"), 2, 0));
        return new GatewayExchange(
                provider,
                grant,
                application.getId(),
                HttpMethod.POST,
                route,
                headers,
                body.getBytes(StandardCharsets.UTF_8),
                "correlation-1",
                null,
                operation);
    }

    private GatewayOutcome query(String body) {
        return service.forward(call(OperationType.QUERY, body));
    }

    // --- reuse ------------------------------------------------------------------

    @Test
    void answersTheSameQueryFromTheStoreTheSecondTime() {
        query("{\"query\":\"{ viewer { login } }\"}");
        var second = query("{\"query\":\"{ viewer { login } }\"}");

        assertThat(second.cacheStatus()).isEqualTo(CacheStatus.HIT);
        assertThat(sent).hasSize(1);
    }

    /** Every query shares the path; the document is what tells them apart. */
    @Test
    void keepsDifferentQueriesApart() {
        query("{\"query\":\"{ viewer { login } }\"}");
        var other = query("{\"query\":\"{ viewer { name } }\"}");

        assertThat(other.cacheStatus()).isEqualTo(CacheStatus.MISS);
        assertThat(sent).hasSize(2);
    }

    @Test
    void neverStoresAMutation() {
        service.forward(call(OperationType.MUTATION, "{\"query\":\"mutation { a }\"}"));
        var again = service.forward(call(OperationType.MUTATION, "{\"query\":\"mutation { a }\"}"));

        assertThat(again.cacheStatus()).isEqualTo(CacheStatus.BYPASS);
        assertThat(sent).hasSize(2);
    }

    /** A mutation may have changed anything a query read, and the path cannot say which. */
    @Test
    void aMutationDropsWhatQueriesAtTheEndpointHadStored() {
        query("{\"query\":\"{ viewer { login } }\"}");
        service.forward(call(OperationType.MUTATION, "{\"query\":\"mutation { rename }\"}"));
        var after = query("{\"query\":\"{ viewer { login } }\"}");

        assertThat(after.cacheStatus()).isEqualTo(CacheStatus.MISS);
        assertThat(sent).hasSize(3);
    }

    /** A GraphQL server answers 200 to what it could not run; storing that shares one failure with everybody. */
    @Test
    void neverStoresAnAnswerCarryingErrorsAndSaysHowMany() {
        willAnswer(answer("{\"data\":null,\"errors\":[{\"message\":\"rate limited\"}]}"));

        var first = query("{\"query\":\"{ viewer { login } }\"}");
        var second = query("{\"query\":\"{ viewer { login } }\"}");

        assertThat(first.headers().getFirst(GatewayTrafficService.GRAPHQL_ERRORS_HEADER))
                .isEqualTo("1");
        assertThat(first.auditDetail()).contains("1 GraphQL error(s)");
        assertThat(second.cacheStatus()).isEqualTo(CacheStatus.MISS);
        assertThat(sent).hasSize(2);
    }

    // --- streams and sockets ---------------------------------------------------

    @Test
    void opensAStreamAndRelaysItsBodyWithoutStoringIt() {
        willAnswer(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "text/event-stream")
                .body("data: {\"a\":1}\n\n")
                .build());

        var opened = service.openStream(
                call(OperationType.SUBSCRIPTION, "{\"query\":\"subscription { a }\"}"),
                java.time.Duration.ofSeconds(5));

        assertThat(opened.status().value()).isEqualTo(200);
        assertThat(opened.headers().getFirst(GatewayTrafficService.CACHE_HEADER))
                .isEqualTo("BYPASS");
        assertThat(opened.secrets()).contains("sk_live_31337");
        var text = opened.body()
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    return new String(bytes, StandardCharsets.UTF_8);
                })
                .collectList()
                .block();
        assertThat(String.join("", text)).contains("data: {\"a\":1}");
    }

    @Test
    void refusesAStreamOverTheApplicationsAllowance() {
        grant.applyQuota(new Grant.Quota(1, 1));
        var exchange = call(OperationType.SUBSCRIPTION, "{\"query\":\"subscription { a }\"}");
        service.openStream(exchange, java.time.Duration.ofSeconds(5));

        assertThatThrownBy(() -> service.openStream(exchange, java.time.Duration.ofSeconds(5)))
                .isInstanceOf(Throttled.class);
    }

    /** A socket carries the credential in its handshake, and nothing of the caller's own socket. */
    @Test
    void preparesASocketCarryingTheCredential() {
        var exchange = call(OperationType.SUBSCRIPTION, "");
        exchange.headers().add("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==");

        var target = service.openSocket(exchange);

        assertThat(target.uri().getScheme()).isEqualTo("wss");
        assertThat(target.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer sk_live_31337");
        assertThat(target.headers().getFirst("Sec-WebSocket-Key")).isNull();
    }

    @Test
    void putsAQueryParameterKeyIntoTheSocketAddress() {
        var keyed = new Credential(
                provider,
                "key",
                new Credential.Strategy(AuthType.API_KEY_QUERY, null, "apikey", null, null, null),
                null,
                true);
        var route = GatewayPath.parse("/gateway/github/graphql", "github", null);
        var exchange = new GatewayExchange(
                provider,
                Fixtures.grant(application, provider, keyed),
                application.getId(),
                HttpMethod.GET,
                route,
                new HttpHeaders(),
                null,
                "correlation-1");

        assertThat(service.openSocket(exchange).uri().getQuery()).contains("apikey=sk_live_31337");
    }

    /** One connection, any number of operations: each is held to the allowance a request is. */
    @Test
    void spendsTheAllowanceOnEveryOperationOverASocket() {
        grant.applyQuota(new Grant.Quota(1, 1));
        var exchange = call(OperationType.SUBSCRIPTION, "");
        service.admitOperation(exchange);

        assertThatThrownBy(() -> service.admitOperation(exchange)).isInstanceOf(Throttled.class);
    }

    // --- a second attempt -------------------------------------------------------

    private static ClientResponse unavailable() {
        return ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).body("").build();
    }

    @Test
    void retriesAQueryThatMetAMomentaryFailure() {
        willAnswer(unavailable());

        var outcome = query("{\"query\":\"{ viewer { login } }\"}");

        assertThat(outcome.status().value()).isEqualTo(200);
        assertThat(sent).hasSize(2);
    }

    /** A mutation that met a 503 may still have run, so it is never sent twice. */
    @Test
    void neverRetriesAMutation() {
        willAnswer(unavailable());

        var outcome = service.forward(call(OperationType.MUTATION, "{\"query\":\"mutation { pay }\"}"));

        assertThat(outcome.status().value()).isEqualTo(503);
        assertThat(sent).hasSize(1);
    }
}
