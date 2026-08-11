package io.janus.oauth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.*;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import io.janus.accounts.TestAccount;
import io.janus.applications.Application;
import io.janus.applications.ApplicationRepository;
import io.janus.audit.AuditService;
import io.janus.security.ApplicationAuthenticator;
import io.janus.security.GatewayPrincipal;

class OAuthTokenServiceTest {
    private final ApplicationAuthenticator authenticator = Mockito.mock(ApplicationAuthenticator.class);
    private final ApplicationRepository applications = Mockito.mock(ApplicationRepository.class);
    private final RefreshTokenRepository refreshTokens = Mockito.mock(RefreshTokenRepository.class);
    private final AuditService audit = Mockito.mock(AuditService.class);
    private final OAuthProperties properties =
            new OAuthProperties(Duration.ofMinutes(15), Duration.ofDays(30), true, 100);
    private final AccessTokenStore accessTokens = new AccessTokenStore(properties);

    private OAuthTokenService service;
    private Application application;
    private GatewayPrincipal principal;
    private final Map<String, RefreshToken> saved = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        service = new OAuthTokenService(authenticator, applications, refreshTokens, accessTokens, properties, audit);
        application = new Application(TestAccount.owner(), "orders", null, true, "hash");
        principal = new GatewayPrincipal(
                application.getId(), "orders", application.getOwner().getId(), Set.of());

        when(refreshTokens.save(any(RefreshToken.class))).thenAnswer(invocation -> {
            RefreshToken token = invocation.getArgument(0);
            saved.put(token.getId().toString(), token);
            return token;
        });
        when(applications.findByIdWithOwner(application.getId())).thenReturn(Optional.of(application));
    }

    private TokenResponse signIn() {
        when(authenticator.authenticate(application.getId(), "jns_secret")).thenReturn(Optional.of(principal));
        return service.clientCredentials(application.getId().toString(), "jns_secret");
    }

    /** Registers a value as the stored token, the way the database would after an exchange. */
    private void storedIs(String value, RefreshToken token) {
        when(refreshTokens.findByTokenHash(AccessTokenStore.digest(value))).thenReturn(Optional.of(token));
    }

    private RefreshToken lastSaved() {
        return saved.values().stream().reduce((first, second) -> second).orElseThrow();
    }

    @Test
    void exchangesClientCredentialsForABearerTokenThatResolves() {
        var response = signIn();

        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(900);
        assertThat(response.accessToken()).startsWith("jnt_");
        assertThat(response.refreshToken()).startsWith("jnr_");
        assertThat(accessTokens.resolve(response.accessToken())).contains(principal);
    }

    /** Neither an unknown client nor a wrong secret says which of the two it was. */
    @Test
    void refusedCredentialsAreInvalidClientAndNothingMore() {
        when(authenticator.authenticate(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.clientCredentials(application.getId().toString(), "jns_wrong"))
                .isInstanceOf(OAuthException.class)
                .hasMessageNotContainingAny("orders", application.getId().toString())
                .extracting(ex -> ((OAuthException) ex).error)
                .isEqualTo("invalid_client");
    }

    @Test
    void aClientIdThatIsNotAnIdentifierFailsAsACredentialRatherThanAsAMalformedRequest() {
        assertThatThrownBy(() -> service.clientCredentials("not-a-uuid", "jns_secret"))
                .isInstanceOf(OAuthException.class)
                .extracting(ex -> ((OAuthException) ex).error)
                .isEqualTo("invalid_client");
    }

    @Test
    void aMissingClientIdIsAMalformedRequest() {
        assertThatThrownBy(() -> service.clientCredentials(null, "jns_secret"))
                .isInstanceOf(OAuthException.class)
                .extracting(ex -> ((OAuthException) ex).error)
                .isEqualTo("invalid_request");
    }

    @Test
    void refreshingIssuesANewPairWithinTheSameFamily() {
        var first = signIn();
        var storedToken = lastSaved();
        storedIs(first.refreshToken(), storedToken);

        var second = service.refresh(first.refreshToken());

        assertThat(second.accessToken()).isNotEqualTo(first.accessToken());
        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(lastSaved().getFamilyId()).isEqualTo(storedToken.getFamilyId());
        assertThat(accessTokens.resolve(second.accessToken())).isPresent();
    }

    /**
     * The property the whole rotation scheme exists for: a value that turns up twice is evidence it
     * leaked, and neither holder keeps the chain.
     */
    @Test
    void aRefreshTokenPresentedTwiceRevokesItsWholeFamily() {
        var first = signIn();
        var storedToken = lastSaved();
        storedIs(first.refreshToken(), storedToken);
        service.refresh(first.refreshToken());

        assertThatThrownBy(() -> service.refresh(first.refreshToken()))
                .isInstanceOf(OAuthException.class)
                .extracting(ex -> ((OAuthException) ex).error)
                .isEqualTo("invalid_grant");

        verify(refreshTokens).deleteByFamilyId(storedToken.getFamilyId());
        assertThat(accessTokens.resolve(first.accessToken())).isEmpty();
    }

    @Test
    void anExpiredRefreshTokenIsRefusedAndRemoved() {
        var expired = new RefreshToken(
                application.getId(),
                "hash",
                UUID.randomUUID(),
                java.time.Instant.now().minusSeconds(1));
        storedIs("jnr_expired", expired);

        assertThatThrownBy(() -> service.refresh("jnr_expired")).isInstanceOf(OAuthException.class);
        verify(refreshTokens).delete(expired);
    }

    @Test
    void anUnknownRefreshTokenIsAnInvalidGrant() {
        when(refreshTokens.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh("jnr_never-issued"))
                .isInstanceOf(OAuthException.class)
                .extracting(ex -> ((OAuthException) ex).error)
                .isEqualTo("invalid_grant");
    }

    /**
     * A token stands for what was true when it was issued. Disabling a service has to end that, or
     * the console's switch would only take effect a month later.
     */
    @Test
    void aRefreshTokenIsRefusedOnceItsServiceIsDisabled() {
        var first = signIn();
        storedIs(first.refreshToken(), lastSaved());
        application.describe("orders", null, false);

        assertThatThrownBy(() -> service.refresh(first.refreshToken())).isInstanceOf(OAuthException.class);
    }

    @Test
    void revokingARefreshTokenDropsTheFamilyAndTheAccessTokensWithIt() {
        var first = signIn();
        var storedToken = lastSaved();
        storedIs(first.refreshToken(), storedToken);

        service.revoke(first.refreshToken());

        verify(refreshTokens).deleteByFamilyId(storedToken.getFamilyId());
        assertThat(accessTokens.resolve(first.accessToken())).isEmpty();
    }

    @Test
    void revokingAnAccessTokenDropsThatTokenOnly() {
        var first = signIn();

        service.revoke(first.accessToken());

        assertThat(accessTokens.resolve(first.accessToken())).isEmpty();
        verify(refreshTokens, never()).deleteByFamilyId(any());
    }

    @Test
    void basicCredentialsAreReadTheWayRfc6749DescribesThem() {
        String header = "Basic " + Base64.getEncoder().encodeToString("client:sec:ret".getBytes());

        assertThat(OAuthTokenService.basicCredentials(header)).hasValueSatisfying(pair -> {
            assertThat(pair[0]).isEqualTo("client");
            // Only the first colon separates; a secret may contain more.
            assertThat(pair[1]).isEqualTo("sec:ret");
        });
        assertThat(OAuthTokenService.basicCredentials("Bearer something")).isEmpty();
        assertThat(OAuthTokenService.basicCredentials(null)).isEmpty();
        assertThat(OAuthTokenService.basicCredentials("Basic not-base64!")).isEmpty();
    }
}
