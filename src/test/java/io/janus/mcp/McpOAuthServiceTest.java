package io.janus.mcp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.time.Duration;
import java.util.*;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.web.util.UriComponentsBuilder;

import io.janus.accounts.*;
import io.janus.audit.AuditAction;
import io.janus.audit.AuditService;
import io.janus.oauth.OAuthException;
import io.janus.shared.NotFoundException;

/**
 * The authorisation server end to end, with the three tables held in memory: a client registers, a
 * browser is sent to the console, a person agrees, and the code is redeemed with its verifier.
 */
class McpOAuthServiceTest {
    private static final String REDIRECT = "http://127.0.0.1:33418/callback";
    private static final String VERIFIER = "a-verifier-that-is-long-enough-to-satisfy-rfc-7636-rules-0123";

    private final McpClientRepository clients = Mockito.mock(McpClientRepository.class);
    private final McpAuthorizationRepository authorizations = Mockito.mock(McpAuthorizationRepository.class);
    private final McpConnectionRepository connections = Mockito.mock(McpConnectionRepository.class);
    private final AccountRepository accounts = Mockito.mock(AccountRepository.class);
    private final AccessScope scope = Mockito.mock(AccessScope.class);
    private final AuditService audit = Mockito.mock(AuditService.class);

    private final Map<UUID, McpClient> clientRows = new HashMap<>();
    private final Map<String, McpAuthorization> authorizationRows = new HashMap<>();
    private final List<McpConnection> connectionRows = new ArrayList<>();

    private final Account account = TestAccount.owner();
    private McpOAuthService oauth;

    @BeforeEach
    void setUp() {
        oauth = new McpOAuthService(
                clients,
                authorizations,
                connections,
                accounts,
                scope,
                audit,
                "https://janus.example.com/",
                "https://console.example.com",
                Duration.ofHours(1),
                Duration.ofDays(30));

        when(clients.save(any(McpClient.class))).thenAnswer(call -> {
            McpClient client = call.getArgument(0);
            clientRows.put(client.getId(), client);
            return client;
        });
        when(clients.findById(any())).thenAnswer(call -> Optional.ofNullable(clientRows.get(call.getArgument(0))));

        when(authorizations.save(any(McpAuthorization.class))).thenAnswer(call -> {
            McpAuthorization row = call.getArgument(0);
            authorizationRows.put(row.getId(), row);
            return row;
        });
        when(authorizations.findById(anyString()))
                .thenAnswer(call -> Optional.ofNullable(authorizationRows.get(call.<String>getArgument(0))));
        when(authorizations.consume(anyString()))
                .thenAnswer(call -> authorizationRows.remove(call.<String>getArgument(0)) == null ? 0 : 1);
        when(authorizations.findByCodeHash(anyString()))
                .thenAnswer(call -> authorizationRows.values().stream()
                        .filter(row -> row.decided() && codeHashOf(row).equals(call.getArgument(0)))
                        .findFirst());

        when(connections.save(any(McpConnection.class))).thenAnswer(call -> {
            connectionRows.add(call.getArgument(0));
            return call.getArgument(0);
        });
        doAnswer(call -> connectionRows.remove(call.<McpConnection>getArgument(0)))
                .when(connections)
                .delete(any(McpConnection.class));
        when(connections.findByAccessTokenHash(anyString()))
                .thenAnswer(call -> byField("accessTokenHash", call.getArgument(0)));
        when(connections.findByRefreshTokenHash(anyString()))
                .thenAnswer(call -> byField("refreshTokenHash", call.getArgument(0)));
        when(connections.findByPreviousRefreshHash(anyString()))
                .thenAnswer(call -> byField("previousRefreshHash", call.getArgument(0)));

        when(accounts.findById(account.getId())).thenReturn(Optional.of(account));
        when(scope.accountId()).thenReturn(account.getId());
        when(scope.ownerFilter()).thenReturn(account.getId());
    }

    /* ── Discovery and registration ────────────────────────────────────── */

    @Test
    void theResourceIsTheMcpEndpointUnderThePublicAddress() {
        assertThat(oauth.issuer()).isEqualTo("https://janus.example.com");
        assertThat(oauth.resource()).isEqualTo("https://janus.example.com/mcp");
    }

    @Test
    void registrationAcceptsLoopbackHttpsAndNativeSchemes() {
        var registered = oauth.register(
                "Claude Code",
                List.of(REDIRECT, "https://claude.ai/api/mcp/auth_callback", "cursor://anysphere.cursor/oauth"));

        assertThat(registered.clientName()).isEqualTo("Claude Code");
        assertThat(registered.redirectUris()).hasSize(3);
    }

    @Test
    void registrationRefusesAddressesACodeMustNeverReach() {
        for (var refused : List.of(
                "http://attacker.example.com/callback",
                "javascript:alert(1)",
                "https://example.com/callback#fragment",
                "not a uri"))
            assertThatThrownBy(() -> oauth.register("x", List.of(refused)))
                    .isInstanceOf(OAuthException.class)
                    .extracting("error")
                    .isEqualTo("invalid_redirect_uri");
        assertThatThrownBy(() -> oauth.register("x", List.of())).isInstanceOf(OAuthException.class);
    }

    @Test
    void aSelfDeclaredNameIsStrippedOfControlCharacters() {
        assertThat(oauth.register("evil\nname", List.of(REDIRECT)).clientName()).isEqualTo("evilname");
        assertThat(oauth.register(null, List.of(REDIRECT)).clientName()).isEqualTo("Unnamed MCP client");
    }

    /* ── Authorisation ─────────────────────────────────────────────────── */

    @Test
    void anUnknownClientIsNeverRedirectedToTheAddressItNamed() {
        var target = oauth.authorize(request(UUID.randomUUID().toString(), REDIRECT));

        assertThat(target.toString()).startsWith("https://console.example.com/mcp/authorize?error=invalid_client");
    }

    @Test
    void anUnregisteredRedirectGoesToTheConsoleNotToTheClient() {
        var client = register();

        var target = oauth.authorize(request(client, "https://attacker.example.com/cb"));

        assertThat(target.getHost()).isEqualTo("console.example.com");
        assertThat(target.getQuery()).contains("error=invalid_redirect_uri");
    }

    @Test
    void aLoopbackRedirectMayNameAnyPort() {
        var client = register();

        var target = oauth.authorize(request(client, "http://127.0.0.1:50123/callback"));

        assertThat(target.getQuery()).startsWith("request=");
    }

    @Test
    void withoutPkceTheClientIsToldSo() {
        var client = register();
        var request = new McpOAuthService.AuthorizeRequest("code", client, REDIRECT, null, null, "xyz", null);

        var target = oauth.authorize(request);

        assertThat(target.toString())
                .startsWith(REDIRECT)
                .contains("error=invalid_request")
                .contains("state=xyz");
    }

    @Test
    void aTokenForAnotherResourceIsRefused() {
        var client = register();
        var request = new McpOAuthService.AuthorizeRequest(
                "code",
                client,
                REDIRECT,
                McpOAuthService.s256(VERIFIER),
                "S256",
                null,
                "https://other.example.com/mcp");

        assertThat(oauth.authorize(request).toString()).contains("error=invalid_target");
    }

    @Test
    void theConsoleDescribesWhereTheCodeWillGo() {
        var requestId = pendingRequest(register());

        var pending = oauth.describe(requestId);

        assertThat(pending.clientName()).isEqualTo("Claude Code");
        assertThat(pending.redirectHost()).isEqualTo("127.0.0.1");
        assertThat(pending.loopback()).isTrue();
    }

    @Test
    void denyingSendsAccessDeniedAndForgetsTheRequest() {
        var requestId = pendingRequest(register());

        var decision = oauth.deny(requestId);

        assertThat(decision.redirectUrl()).startsWith(REDIRECT).contains("error=access_denied");
        verify(authorizations).delete(any(McpAuthorization.class));
    }

    @Test
    void aRequestCanBeDecidedOnlyOnce() {
        var requestId = pendingRequest(register());
        oauth.approve(requestId);

        assertThatThrownBy(() -> oauth.approve(requestId)).isInstanceOf(NotFoundException.class);
    }

    /* ── Tokens ────────────────────────────────────────────────────────── */

    @Test
    void theWholeFlowIssuesATokenThatActsAsThePersonWhoAgreed() {
        var client = register();
        var code = approvedCode(client);

        var tokens = oauth.exchangeCode(code, REDIRECT, client, VERIFIER, "https://janus.example.com/mcp/");

        assertThat(tokens.accessToken()).startsWith("jma_");
        assertThat(tokens.refreshToken()).startsWith("jmr_");
        assertThat(tokens.expiresIn()).isEqualTo(3600);
        var caller = oauth.authenticate(tokens.accessToken()).orElseThrow();
        assertThat(caller.user().id()).isEqualTo(account.getId());
        assertThat(caller.clientName()).isEqualTo("Claude Code");
        verify(audit).recordAdmin(AuditAction.MCP_CLIENT_AUTHORIZED, null, "Claude Code");
    }

    @Test
    void aCodeIsOneAttemptEvenWhenTheAttemptFails() {
        var client = register();
        var code = approvedCode(client);

        assertThatThrownBy(() -> oauth.exchangeCode(code, REDIRECT, client, "wrong-verifier", null))
                .isInstanceOf(OAuthException.class)
                .extracting("error")
                .isEqualTo("invalid_grant");
        assertThatThrownBy(() -> oauth.exchangeCode(code, REDIRECT, client, VERIFIER, null))
                .isInstanceOf(OAuthException.class);
    }

    @Test
    void aCodeIsRedeemedOnlyByTheClientAndAddressItWasIssuedTo() {
        var client = register();
        var other = register();

        assertThatThrownBy(() -> oauth.exchangeCode(approvedCode(client), REDIRECT, other, VERIFIER, null))
                .isInstanceOf(OAuthException.class);
        assertThatThrownBy(() ->
                        oauth.exchangeCode(approvedCode(client), "http://127.0.0.1:1/callback", client, VERIFIER, null))
                .isInstanceOf(OAuthException.class);
    }

    @Test
    void refreshingRotatesBothTokens() {
        var client = register();
        var first = oauth.exchangeCode(approvedCode(client), REDIRECT, client, VERIFIER, null);

        var second = oauth.refresh(first.refreshToken(), client, null);

        assertThat(second.accessToken()).isNotEqualTo(first.accessToken());
        assertThat(oauth.authenticate(first.accessToken())).isEmpty();
        assertThat(oauth.authenticate(second.accessToken())).isPresent();
    }

    @Test
    void aRefreshTokenPresentedTwiceEndsTheConnection() {
        var client = register();
        var first = oauth.exchangeCode(approvedCode(client), REDIRECT, client, VERIFIER, null);
        var second = oauth.refresh(first.refreshToken(), client, null);

        assertThatThrownBy(() -> oauth.refresh(first.refreshToken(), client, null))
                .isInstanceOf(OAuthException.class);
        assertThat(connectionRows).isEmpty();
        assertThat(oauth.authenticate(second.accessToken())).isEmpty();
    }

    @Test
    void aDisabledAccountStopsItsAssistantsOnTheNextCall() {
        var client = register();
        var tokens = oauth.exchangeCode(approvedCode(client), REDIRECT, client, VERIFIER, null);

        account.describe(account.getDisplayName(), account.getEmail(), false);

        assertThat(oauth.authenticate(tokens.accessToken())).isEmpty();
    }

    @Test
    void aPasswordChangeEndsEveryConsentGivenBeforeIt() throws Exception {
        var client = register();
        var tokens = oauth.exchangeCode(approvedCode(client), REDIRECT, client, VERIFIER, null);

        Thread.sleep(5);
        account.changePassword("new-hash");

        assertThat(oauth.authenticate(tokens.accessToken())).isEmpty();
        assertThatThrownBy(() -> oauth.refresh(tokens.refreshToken(), client, null))
                .isInstanceOf(OAuthException.class);
    }

    @Test
    void revokingEitherTokenEndsTheConnection() {
        var client = register();
        var tokens = oauth.exchangeCode(approvedCode(client), REDIRECT, client, VERIFIER, null);

        oauth.revoke(tokens.refreshToken());

        assertThat(oauth.authenticate(tokens.accessToken())).isEmpty();
    }

    @Test
    void somethingThatIsNotAnMcpTokenIsNotLookedUp() {
        assertThat(oauth.authenticate("jns_an-application-key")).isEmpty();
        verifyNoInteractions(connections);
    }

    /* ── Helpers ───────────────────────────────────────────────────────── */

    private String register() {
        return oauth.register("Claude Code", List.of(REDIRECT)).clientId();
    }

    private static McpOAuthService.AuthorizeRequest request(String clientId, String redirect) {
        return new McpOAuthService.AuthorizeRequest(
                "code", clientId, redirect, McpOAuthService.s256(VERIFIER), "S256", "state-1", null);
    }

    private String pendingRequest(String clientId) {
        var target = oauth.authorize(request(clientId, REDIRECT));
        return UriComponentsBuilder.fromUri(target).build().getQueryParams().getFirst("request");
    }

    private String approvedCode(String clientId) {
        var decision = oauth.approve(pendingRequest(clientId));
        return UriComponentsBuilder.fromUri(URI.create(decision.redirectUrl()))
                .build()
                .getQueryParams()
                .getFirst("code");
    }

    private static String codeHashOf(McpAuthorization row) {
        return (String) field(row, "codeHash");
    }

    private Optional<McpConnection> byField(String name, String hash) {
        return connectionRows.stream()
                .filter(row -> hash.equals(field(row, name)))
                .findFirst();
    }

    private static Object field(Object target, String name) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
