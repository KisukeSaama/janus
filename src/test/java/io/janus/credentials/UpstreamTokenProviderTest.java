package io.janus.credentials;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import io.janus.accounts.TestAccount;
import io.janus.openbao.OpenBaoClient;
import io.janus.providers.Provider;

/**
 * The client-credentials exchange.
 *
 * <p>This is the one place where a stored secret is deliberately sent somewhere other than the API
 * it belongs to, so most of what is asserted here is about containment: the secret goes to the
 * configured token endpoint in the form that endpoint expects, and whatever comes back — including
 * a refusal that quotes the credentials straight back — never travels any further.
 */
class UpstreamTokenProviderTest {
    private final UpstreamTokenCache cache = new UpstreamTokenCache();

    private final List<ClientRequest> sent = new ArrayList<>();
    private final Deque<Supplier<Mono<ClientResponse>>> answers = new ArrayDeque<>();

    private UpstreamTokenProvider tokens;

    private final Provider provider = new Provider(
            TestAccount.owner(),
            "Spotify",
            "spotify",
            "https://api.spotify.com",
            true,
            new Provider.TrafficPolicy(true, 0, 0, 0));

    @BeforeEach
    void setUp() {
        var web = WebClient.builder()
                .exchangeFunction(request -> {
                    sent.add(request);
                    var next = answers.poll();
                    return next == null ? Mono.just(json("{\"access_token\":\"token-1\"}")) : next.get();
                })
                .build();
        // OpenBao is only reached for the refresh token of a consented connection, which this
        // exchange is not: a client-credentials credential holds everything it needs in one value.
        tokens = new UpstreamTokenProvider(
                web, cache, new ObjectMapper(), org.mockito.Mockito.mock(OpenBaoClient.class));
    }

    private static ClientResponse json(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    private void willAnswer(ClientResponse response) {
        answers.add(() -> Mono.just(response));
    }

    private Credential credential(TokenClientAuth auth, String scopes) {
        return new Credential(
                provider,
                "key",
                new Credential.Strategy(
                        AuthType.OAUTH2_CLIENT_CREDENTIALS,
                        null,
                        null,
                        "https://auth.example.com/oauth/token",
                        scopes,
                        auth),
                null,
                true);
    }

    private static String header(ClientRequest request, String name) {
        return request.headers().getFirst(name);
    }

    // --- obtaining a token ---------------------------------------------------

    @Test
    void exchangesTheStoredPairForAToken() {
        willAnswer(json("{\"access_token\":\"token-1\",\"expires_in\":3600}"));

        assertThat(tokens.tokenFor(credential(TokenClientAuth.BASIC, null), "client-id:client-secret"))
                .isEqualTo("token-1");
        assertThat(sent.getFirst().url()).hasToString("https://auth.example.com/oauth/token");
    }

    @Test
    void presentsTheClientPairAsBasicAuthenticationByDefault() {
        tokens.tokenFor(credential(TokenClientAuth.BASIC, null), "client-id:client-secret");

        String expected = "Basic "
                + Base64.getEncoder().encodeToString("client-id:client-secret".getBytes(StandardCharsets.UTF_8));
        assertThat(header(sent.getFirst(), HttpHeaders.AUTHORIZATION)).isEqualTo(expected);
    }

    @Test
    void doesNotSendAnAuthorizationHeaderWhenTheEndpointWantsThePairInTheBody() {
        tokens.tokenFor(credential(TokenClientAuth.POST, null), "client-id:client-secret");

        assertThat(header(sent.getFirst(), HttpHeaders.AUTHORIZATION)).isNull();
    }

    @Test
    void asksForFormEncodedAndAcceptsJson() {
        tokens.tokenFor(credential(TokenClientAuth.BASIC, null), "client-id:client-secret");

        assertThat(header(sent.getFirst(), HttpHeaders.CONTENT_TYPE))
                .isEqualTo(MediaType.APPLICATION_FORM_URLENCODED_VALUE);
        assertThat(header(sent.getFirst(), HttpHeaders.ACCEPT)).contains(MediaType.APPLICATION_JSON_VALUE);
    }

    // --- holding on to it ----------------------------------------------------

    @Test
    void asksForATokenOnceAndThenReusesIt() {
        willAnswer(json("{\"access_token\":\"token-1\",\"expires_in\":3600}"));
        var credential = credential(TokenClientAuth.BASIC, null);

        tokens.tokenFor(credential, "client-id:client-secret");
        String second = tokens.tokenFor(credential, "client-id:client-secret");

        assertThat(second).isEqualTo("token-1");
        assertThat(sent).hasSize(1);
    }

    /**
     * A provider that states no lifetime gets a conservative one assumed for it rather than none:
     * holding nothing would mean an exchange per request, which is what the cache exists to avoid.
     */
    @Test
    void assumesAConservativeLifetimeWhenTheProviderStatesNone() {
        willAnswer(json("{\"access_token\":\"token-1\"}"));
        var credential = credential(TokenClientAuth.BASIC, null);

        tokens.tokenFor(credential, "client-id:client-secret");

        assertThat(cache.lookup(credential.getId())).contains("token-1");
        assertThat(UpstreamTokenCache.usableSeconds(null))
                .isEqualTo(UpstreamTokenCache.ASSUMED_LIFETIME_SECONDS - UpstreamTokenCache.SAFETY_MARGIN_SECONDS);
    }

    // --- refusals ------------------------------------------------------------

    /**
     * A token endpoint that rejects client credentials routinely quotes them back in the body it
     * returns. Only the status may ever reach the caller or the journal.
     */
    @Test
    void reportsARefusalByStatusWithoutTheBodyThatCameWithIt() {
        willAnswer(ClientResponse.create(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body("{\"error\":\"invalid_client\",\"description\":\"client-secret is wrong\"}")
                .build());

        assertThatThrownBy(() -> tokens.tokenFor(credential(TokenClientAuth.BASIC, null), "client-id:client-secret"))
                .isInstanceOf(TokenExchangeException.class)
                .hasMessageContaining("status 401")
                .hasMessageNotContaining("client-secret");
    }

    /** Repeating a refusal every request turns one misconfiguration into a burst against the endpoint. */
    @Test
    void doesNotRepeatARefusalItJustReceived() {
        willAnswer(ClientResponse.create(HttpStatus.UNAUTHORIZED).body("").build());
        var credential = credential(TokenClientAuth.BASIC, null);

        assertThatThrownBy(() -> tokens.tokenFor(credential, "client-id:client-secret"))
                .isInstanceOf(TokenExchangeException.class);
        assertThatThrownBy(() -> tokens.tokenFor(credential, "client-id:client-secret"))
                .isInstanceOf(TokenExchangeException.class)
                .hasMessageContaining("moments ago");

        assertThat(sent).hasSize(1);
    }

    @Test
    void refusesAStoredSecretThatIsNotAClientPair() {
        assertThatThrownBy(() -> tokens.tokenFor(credential(TokenClientAuth.BASIC, null), "just-a-key"))
                .isInstanceOf(TokenExchangeException.class)
                .hasMessageContaining("client_id:client_secret");
        assertThat(sent).isEmpty();
    }

    @Test
    void treatsAnAnswerWithoutATokenAsARefusal() {
        willAnswer(json("{\"token_type\":\"Bearer\"}"));

        assertThatThrownBy(() -> tokens.tokenFor(credential(TokenClientAuth.BASIC, null), "client-id:client-secret"))
                .isInstanceOf(TokenExchangeException.class);
    }

    @Test
    void treatsAnAnswerThatIsNotJsonAsARefusal() {
        willAnswer(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE)
                .body("<html>login page</html>")
                .build());

        assertThatThrownBy(() -> tokens.tokenFor(credential(TokenClientAuth.BASIC, null), "client-id:client-secret"))
                .isInstanceOf(TokenExchangeException.class);
    }

    @Test
    void reportsAnUnreachableEndpointWithoutTheTransportDetail() {
        answers.add(() -> Mono.error(new IllegalStateException("Connection refused to 10.0.0.5:8443")));

        assertThatThrownBy(() -> tokens.tokenFor(credential(TokenClientAuth.BASIC, null), "client-id:client-secret"))
                .isInstanceOf(TokenExchangeException.class)
                .hasMessage("The token endpoint could not be reached");
    }
}
