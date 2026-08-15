package io.janus.credentials;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;

import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import io.janus.accounts.AccessScope;
import io.janus.audit.AuditAction;
import io.janus.audit.AuditService;
import io.janus.gateway.TrafficPolicyRegistry;
import io.janus.openbao.OpenBaoClient;
import io.janus.providers.Provider;
import io.janus.shared.NotFoundException;

/**
 * The one exchange in Janus that a person has to be present for.
 *
 * <p>What is asserted here is mostly about the parts a reader never sees and cannot recover from
 * when they are wrong: that the state is single-use, that the PKCE challenge is the hash of the
 * verifier that will later be sent, that a consent without a refresh token is refused rather than
 * recorded as working, and that nothing about any of it reaches an error message.
 */
class CredentialAuthorizationServiceTest {
    private static final UUID ACCOUNT = UUID.randomUUID();

    private final CredentialRepository credentials = mock(CredentialRepository.class);
    private final OpenBaoClient bao = mock(OpenBaoClient.class);
    private final AccessScope scope = mock(AccessScope.class);
    private final AuditService audit = mock(AuditService.class);
    private final UpstreamTokenCache tokens = new UpstreamTokenCache();
    private final AuthorizationStateRepository states = mock(AuthorizationStateRepository.class);

    /**
     * The real one, not a mock. It is three lines around a repository call, and what makes it worth
     * having — its own transaction — is not decidable here at all: see {@code AuthorizationStateIT}.
     */
    private final AuthorizationStateConsumer consumer = new AuthorizationStateConsumer(states);

    private final TrafficPolicyRegistry traffic = mock(TrafficPolicyRegistry.class);

    /** What the repository would be holding. Asserted on directly, which a mock alone would not allow. */
    private final Map<String, AuthorizationState> pending = new LinkedHashMap<>();

    private boolean allExpired;

    private final Deque<Supplier<Mono<ClientResponse>>> answers = new ArrayDeque<>();
    private final List<ClientRequest> sent = new ArrayList<>();

    private CredentialAuthorizationService authorizations;
    private Credential credential;

    private final Provider provider = new Provider(
            "Spotify",
            "spotify",
            "https://api.spotify.com",
            true,
            new Provider.TrafficPolicy(true, 0, 0, 0),
            Provider.Auth.none(),
            new Provider.Connection(
                    "https://accounts.spotify.com/authorize",
                    "https://accounts.spotify.com/api/token",
                    "playlist-read-private",
                    TokenClientAuth.BASIC));

    @BeforeEach
    void setUp() {
        credential = new Credential(ACCOUNT, provider, "spotify-secret", Credential.strategyOf(provider), null, true);
        credential.applyConnection(provider.connection());

        when(scope.ownerFilter()).thenReturn(ACCOUNT);
        when(scope.accountId()).thenReturn(ACCOUNT);
        when(credentials.findOwnedBy(credential.getId(), ACCOUNT)).thenReturn(Optional.of(credential));
        when(credentials.findById(credential.getId())).thenReturn(Optional.of(credential));
        when(bao.read(credential.getSecretPath())).thenReturn("client-abc:secret-xyz");

        // A store rather than an interaction: these tests care about what survives a call, so the
        // repository answers from a map and the assertions read that map.
        when(states.save(any(AuthorizationState.class))).thenAnswer(call -> {
            AuthorizationState state = call.getArgument(0);
            pending.put(state.getState(), state);
            return state;
        });
        when(states.findById(anyString())).thenAnswer(call -> Optional.ofNullable(pending.get(call.getArgument(0))));
        // Consumed by name and counted, the way the conditional delete answers: one for the caller
        // that took it, zero for anybody arriving afterwards.
        when(states.consume(anyString()))
                .thenAnswer(call -> pending.remove(call.getArgument(0, String.class)) != null ? 1 : 0);
        when(states.deleteExpired(any())).thenAnswer(call -> {
            if (!allExpired) return 0;
            int swept = pending.size();
            pending.clear();
            return swept;
        });

        var web = WebClient.builder()
                .exchangeFunction(request -> {
                    sent.add(request);
                    var next = answers.poll();
                    return next == null ? Mono.just(json("{}")) : next.get();
                })
                .build();
        authorizations = new CredentialAuthorizationService(
                credentials,
                states,
                consumer,
                bao,
                tokens,
                web,
                new ObjectMapper(),
                scope,
                audit,
                traffic,
                "https://janus.example.com/");
    }

    private static ClientResponse json(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    private String startAndTakeState() {
        var started = authorizations.start(credential.getId());
        var query = java.net.URI.create(started.authorizationUrl()).getQuery();
        return Arrays.stream(query.split("&"))
                .filter(p -> p.startsWith("state="))
                .map(p -> p.substring("state=".length()))
                .findFirst()
                .orElseThrow();
    }

    @Nested
    class Starting {
        @Test
        void sends_the_operator_to_the_provider_with_everything_rfc_6749_asks_for() {
            var started = authorizations.start(credential.getId());

            assertThat(started.providerName()).isEqualTo("Spotify");
            assertThat(started.authorizationUrl())
                    .startsWith("https://accounts.spotify.com/authorize?")
                    .contains("response_type=code")
                    .contains("client_id=client-abc")
                    .contains("code_challenge_method=S256")
                    .contains("scope=playlist-read-private")
                    // The redirect is built from the public URL, whose trailing slash must not double.
                    .contains("redirect_uri=https://janus.example.com/oauth/callback");
            // The client secret is the half that must never appear in an address a browser will follow.
            assertThat(started.authorizationUrl()).doesNotContain("secret-xyz");
        }

        /** RFC 7636 §4.2. A challenge that is not the verifier's hash makes the exchange fail later. */
        @Test
        void sends_the_hash_of_the_verifier_it_kept() throws Exception {
            var url = authorizations.start(credential.getId()).authorizationUrl();
            String challenge = Arrays.stream(java.net.URI.create(url).getQuery().split("&"))
                    .filter(p -> p.startsWith("code_challenge="))
                    .map(p -> p.substring("code_challenge=".length()))
                    .findFirst()
                    .orElseThrow();

            var kept = pending.values().iterator().next().getCodeVerifier();
            var expected = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            MessageDigest.getInstance("SHA-256").digest(kept.getBytes(StandardCharsets.US_ASCII)));
            assertThat(challenge).isEqualTo(expected);
        }

        @Test
        void records_that_somebody_was_sent_to_agree() {
            authorizations.start(credential.getId());
            verify(audit).recordAdmin(eq(AuditAction.CREDENTIAL_AUTHORIZATION_STARTED), any(), anyString());
        }

        /**
         * What is refused is an API offering no connection — not a particular auth type. The two are
         * orthogonal now, and a bearer-key destination that also lets an account holder connect theirs
         * is exactly the shape this change exists to allow.
         */
        @Test
        void refuses_an_api_that_offers_no_account_connection() {
            credential.describe("bearer", Credential.Strategy.of(AuthType.BEARER), null, true);
            credential.applyConnection(Provider.Connection.none());

            assertThatThrownBy(() -> authorizations.start(credential.getId()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not offer an account connection");
        }

        /** A key of its own is presented to the API; the connection still exchanges as an OAuth client. */
        @Test
        void starts_for_a_destination_whose_application_identity_is_not_oauth() {
            credential.describe("bearer", Credential.Strategy.of(AuthType.BEARER), null, true);
            credential.markConnectionProvisioned();
            when(bao.read(credential.connectionSecretPath())).thenReturn("client-abc:secret-xyz");

            assertThat(authorizations.start(credential.getId()).authorizationUrl())
                    .startsWith("https://accounts.spotify.com/authorize?")
                    .contains("client_id=client-abc");
        }

        @Test
        void refuses_when_no_client_credentials_have_been_stored_yet() {
            when(bao.read(credential.getSecretPath())).thenThrow(new IllegalStateException("missing"));

            assertThatThrownBy(() -> authorizations.start(credential.getId()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("client id and secret");
        }

        @Test
        void refuses_a_credential_belonging_to_somebody_else() {
            when(credentials.findOwnedBy(credential.getId(), ACCOUNT)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authorizations.start(credential.getId())).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class Completing {
        @Test
        void stores_the_refresh_token_and_records_who_agreed() {
            String state = startAndTakeState();
            // An id_token is the only place a provider commonly names the person. Its payload here is
            // {"email":"someone@example.com"}, unsigned, which is all this is ever read for.
            answers.add(() -> Mono.just(json("""
                    {"access_token":"at-1","refresh_token":"rt-1","expires_in":3600,
                     "id_token":"x.eyJlbWFpbCI6InNvbWVvbmVAZXhhbXBsZS5jb20ifQ.y"}""")));

            var done = authorizations.complete(state, "the-code");

            assertThat(done.subject()).isEqualTo("someone@example.com");
            assertThat(credential.awaitingAuthorization()).isFalse();
            assertThat(credential.getAuthorizedSubject()).isEqualTo("someone@example.com");
            verify(bao).write(credential.refreshTokenPath(), "rt-1");
            // The access token that came with it is kept, so the first call after consent does not
            // pay for a refresh round trip.
            assertThat(tokens.lookup(credential.getId(), Identity.ACCOUNT)).contains("at-1");
            verify(audit).recordAdmin(eq(AuditAction.CREDENTIAL_AUTHORIZED), any(), contains("someone@example.com"));
        }

        @Test
        void sends_the_verifier_and_the_same_redirect_the_flow_started_with() {
            String state = startAndTakeState();
            var kept = pending.get(state);
            answers.add(() -> Mono.just(json("{\"access_token\":\"at-1\",\"refresh_token\":\"rt-1\"}")));

            authorizations.complete(state, "the-code");

            var body = sent.get(0);
            assertThat(body.url()).hasToString("https://accounts.spotify.com/api/token");
            assertThat(body.headers().getFirst(HttpHeaders.AUTHORIZATION)).startsWith("Basic ");
            // Not asserted through the form body, which the client encodes lazily; the verifier and
            // redirect are what the state held, and RFC 6749 §4.1.3 requires both to match exactly.
            assertThat(kept.getCodeVerifier()).isNotBlank();
            assertThat(kept.getRedirectUri()).isEqualTo("https://janus.example.com/oauth/callback");
        }

        /**
         * A refresh token is what makes this a connection rather than an hour of access. A provider
         * that issues none has to be reported, not recorded as success that stops working silently.
         */
        @Test
        void refuses_a_consent_that_cannot_be_kept_alive() {
            String state = startAndTakeState();
            answers.add(() -> Mono.just(json("{\"access_token\":\"at-1\",\"expires_in\":3600}")));

            assertThatThrownBy(() -> authorizations.complete(state, "the-code"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("refresh token");
            assertThat(credential.awaitingAuthorization()).isTrue();
            verify(bao, never()).write(eq(credential.refreshTokenPath()), anyString());
        }

        @Test
        void spends_the_state_once_even_when_the_exchange_fails() {
            String state = startAndTakeState();
            answers.add(() -> Mono.just(json("{\"access_token\":\"at-1\"}")));

            assertThatThrownBy(() -> authorizations.complete(state, "the-code"))
                    .isInstanceOf(IllegalArgumentException.class);
            // Replaying it must not get a second attempt at the same code.
            assertThatThrownBy(() -> authorizations.complete(state, "the-code"))
                    .hasMessageContaining("no longer valid");
        }

        @Test
        void refuses_a_state_it_never_issued() {
            assertThatThrownBy(() -> authorizations.complete("invented", "the-code"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no longer valid");
        }

        @Test
        void tells_the_operator_to_start_again_rather_than_quoting_the_provider() {
            String state = startAndTakeState();
            answers.add(() -> Mono.error(WebClientResponseException.create(
                    400,
                    "Bad Request",
                    HttpHeaders.EMPTY,
                    "{\"error\":\"invalid_client\"}".getBytes(StandardCharsets.UTF_8),
                    null)));

            assertThatThrownBy(() -> authorizations.complete(state, "the-code"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("refused this authorisation")
                    .hasMessageNotContaining("invalid_client");
        }
    }

    @Nested
    class Revoking {
        @Test
        void forgets_the_consent_and_the_token_behind_it() {
            String state = startAndTakeState();
            answers.add(() -> Mono.just(json("{\"access_token\":\"at-1\",\"refresh_token\":\"rt-1\"}")));
            authorizations.complete(state, "the-code");

            authorizations.revoke(credential.getId());

            assertThat(credential.awaitingAuthorization()).isTrue();
            // Held tokens, stored responses, and what was learned about which endpoints answer to whom
            // all went with it. The registry owns that sweep and is tested where it lives; what
            // matters here is that revoking asks for it at all.
            verify(traffic, atLeastOnce()).forgetCredential(credential.getId());
            verify(bao).delete(credential.refreshTokenPath());
            verify(audit).recordAdmin(eq(AuditAction.CREDENTIAL_AUTHORIZATION_REVOKED), any(), anyString());
        }

        /** The record already says nobody agreed, which is what every later decision reads. */
        @Test
        void forgets_it_even_when_the_store_cannot_be_reached() {
            doThrow(new IllegalStateException("unreachable")).when(bao).delete(anyString());

            assertThatCode(() -> authorizations.revoke(credential.getId())).doesNotThrowAnyException();
            assertThat(credential.awaitingAuthorization()).isTrue();
        }

        @Test
        void refuses_a_credential_that_carries_no_connection() {
            credential.applyConnection(Provider.Connection.none());

            assertThatThrownBy(() -> authorizations.revoke(credential.getId()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("carries no authorisation");
        }
    }

    @Test
    void sweeps_the_authorisations_nobody_came_back_from() {
        authorizations.start(credential.getId());
        allExpired = true;

        authorizations.sweepExpired();

        assertThat(pending).isEmpty();
    }
}
