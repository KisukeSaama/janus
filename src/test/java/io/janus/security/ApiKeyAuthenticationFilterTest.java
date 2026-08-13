package io.janus.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.ObjectMapper;

import io.janus.applications.Application;
import io.janus.applications.ApplicationRepository;
import io.janus.audit.AuditAction;
import io.janus.audit.AuditActor;
import io.janus.audit.AuditService;
import io.janus.oauth.AccessTokenStore;
import io.janus.oauth.OAuthProperties;

class ApiKeyAuthenticationFilterTest {
    private static final String VALID_KEY = "jns_a-valid-looking-application-key";

    private final ApplicationRepository applications = Mockito.mock(ApplicationRepository.class);
    private final AuditService audit = Mockito.mock(AuditService.class);
    private final PasswordEncoder encoder = Mockito.spy(new BCryptPasswordEncoder(4));
    private final ApiKeyCache cache = new ApiKeyCache();
    private AuthenticationThrottle throttle;
    private AccessTokenStore accessTokens;
    private ApiKeyAuthenticationFilter filter;
    private Application application;

    @BeforeEach
    void setUp() {
        throttle = new AuthenticationThrottle(3, 300, 900);
        accessTokens = new AccessTokenStore(
                new OAuthProperties(java.time.Duration.ofMinutes(15), java.time.Duration.ofDays(30), true, 100));
        filter = new ApiKeyAuthenticationFilter(
                new ApplicationAuthenticator(applications, encoder, cache),
                accessTokens,
                throttle,
                audit,
                new ObjectMapper());
        application =
                new Application(io.janus.accounts.TestAccount.owner(), "orders", null, true, encoder.encode(VALID_KEY));
        when(applications.findByIdWithOwner(application.getId())).thenReturn(Optional.of(application));
        clearInvocations(encoder);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest gatewayRequest(String applicationId, String key) {
        var request = new MockHttpServletRequest("GET", "/gateway/example/v1/items");
        request.setRemoteAddr("203.0.113.10");
        if (applicationId != null) request.addHeader(ApiKeyAuthenticationFilter.APP_HEADER, applicationId);
        if (key != null) request.addHeader(ApiKeyAuthenticationFilter.KEY_HEADER, key);
        return request;
    }

    private MockHttpServletResponse invoke(MockHttpServletRequest request, FilterChain chain) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void authenticatesAValidApplicationKey() throws Exception {
        var chain = Mockito.mock(FilterChain.class);
        var response = invoke(gatewayRequest(application.getId().toString(), VALID_KEY), chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void exposesTheAuthenticatedApplicationToTheGateway() throws Exception {
        var captured = new GatewayPrincipal[1];
        FilterChain chain = (req, res) -> captured[0] = (GatewayPrincipal)
                SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        invoke(gatewayRequest(application.getId().toString(), VALID_KEY), chain);

        assertThat(captured[0].applicationId()).isEqualTo(application.getId());
        assertThat(captured[0].applicationName()).isEqualTo("orders");
    }

    /** The other door: a token from the exchange, which is what a browser or an SDK will present. */
    @Test
    void authenticatesABearerTokenFromTheExchange() throws Exception {
        var principal = new GatewayPrincipal(application.getId(), "orders", UUID.randomUUID(), Set.of());
        String token = accessTokens.issue(principal, 600);

        var request = new MockHttpServletRequest("GET", "/gateway/example/v1/items");
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("Authorization", "Bearer " + token);
        var chain = Mockito.mock(FilterChain.class);

        assertThat(invoke(request, chain).getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
        // The cheap path: resolving a token must not cost a hash comparison.
        verify(encoder, never()).matches(any(), any());
    }

    @Test
    void refusesAnUnknownOrExpiredBearerToken() throws Exception {
        var request = new MockHttpServletRequest("GET", "/gateway/example/v1/items");
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("Authorization", "Bearer jnt_never-issued");

        assertThat(invoke(request, Mockito.mock(FilterChain.class)).getStatus()).isEqualTo(401);
    }

    /**
     * A page at an origin the service never declared holds credentials it should not have. Declaring
     * none is the default and means any, so this only bites once somebody has narrowed it.
     */
    @Test
    void refusesCredentialsPresentedFromAnUndeclaredOrigin() throws Exception {
        var restricted = new GatewayPrincipal(
                application.getId(), "orders", UUID.randomUUID(), Set.of("https://app.example.com"));
        String token = accessTokens.issue(restricted, 600);

        var allowed = new MockHttpServletRequest("GET", "/gateway/example/v1/items");
        allowed.setRemoteAddr("203.0.113.10");
        allowed.addHeader("Authorization", "Bearer " + token);
        allowed.addHeader("Origin", "https://app.example.com");
        assertThat(invoke(allowed, Mockito.mock(FilterChain.class)).getStatus()).isEqualTo(200);

        var elsewhere = new MockHttpServletRequest("GET", "/gateway/example/v1/items");
        elsewhere.setRemoteAddr("203.0.113.10");
        elsewhere.addHeader("Authorization", "Bearer " + token);
        elsewhere.addHeader("Origin", "https://evil.example.com");
        assertThat(invoke(elsewhere, Mockito.mock(FilterChain.class)).getStatus())
                .isEqualTo(401);
    }

    /** Origins restrict pages. A server, a cron or a script sends no Origin and is not a browser. */
    @Test
    void aCallWithoutAnOriginIsNotSubjectToTheOriginList() throws Exception {
        var restricted = new GatewayPrincipal(
                application.getId(), "orders", UUID.randomUUID(), Set.of("https://app.example.com"));
        String token = accessTokens.issue(restricted, 600);

        var request = new MockHttpServletRequest("GET", "/gateway/example/v1/items");
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("Authorization", "Bearer " + token);

        assertThat(invoke(request, Mockito.mock(FilterChain.class)).getStatus()).isEqualTo(200);
    }

    @Test
    void clearsTheSecurityContextAfterTheRequest() throws Exception {
        invoke(gatewayRequest(application.getId().toString(), VALID_KEY), Mockito.mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void refusesAWrongKey() throws Exception {
        var chain = Mockito.mock(FilterChain.class);
        var response = invoke(gatewayRequest(application.getId().toString(), "jns_wrong"), chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void refusesADisabledApplication() throws Exception {
        application.describe("orders", null, false);
        assertThat(invoke(gatewayRequest(application.getId().toString(), VALID_KEY), Mockito.mock(FilterChain.class))
                        .getStatus())
                .isEqualTo(401);
    }

    @Test
    void refusesAnUnknownApplication() throws Exception {
        var unknown = UUID.randomUUID();
        when(applications.findByIdWithOwner(unknown)).thenReturn(Optional.empty());
        assertThat(invoke(gatewayRequest(unknown.toString(), VALID_KEY), Mockito.mock(FilterChain.class))
                        .getStatus())
                .isEqualTo(401);
    }

    @Test
    void anUnknownApplicationStillPaysAHashComparison() throws Exception {
        // Otherwise the response time discloses which application identifiers exist.
        var unknown = UUID.randomUUID();
        when(applications.findByIdWithOwner(unknown)).thenReturn(Optional.empty());
        invoke(gatewayRequest(unknown.toString(), VALID_KEY), Mockito.mock(FilterChain.class));
        verify(encoder).matches(eq(VALID_KEY), anyString());
    }

    @Test
    void refusesMalformedOrMissingHeaders() throws Exception {
        assertThat(invoke(gatewayRequest("not-a-uuid", VALID_KEY), Mockito.mock(FilterChain.class))
                        .getStatus())
                .isEqualTo(401);
        assertThat(invoke(gatewayRequest(application.getId().toString(), null), Mockito.mock(FilterChain.class))
                        .getStatus())
                .isEqualTo(401);
        assertThat(invoke(gatewayRequest(null, VALID_KEY), Mockito.mock(FilterChain.class))
                        .getStatus())
                .isEqualTo(401);
    }

    @Test
    void aRepeatedValidKeyIsVerifiedOnceAndThenServedFromTheCache() throws Exception {
        invoke(gatewayRequest(application.getId().toString(), VALID_KEY), Mockito.mock(FilterChain.class));
        invoke(gatewayRequest(application.getId().toString(), VALID_KEY), Mockito.mock(FilterChain.class));
        invoke(gatewayRequest(application.getId().toString(), VALID_KEY), Mockito.mock(FilterChain.class));
        verify(encoder, times(1)).matches(eq(VALID_KEY), anyString());
    }

    @Test
    void aRotatedKeyIsNotServedFromTheCache() throws Exception {
        invoke(gatewayRequest(application.getId().toString(), VALID_KEY), Mockito.mock(FilterChain.class));
        application.rotateApiKey(encoder.encode("jns_rotated"));
        clearInvocations(encoder);

        var response =
                invoke(gatewayRequest(application.getId().toString(), VALID_KEY), Mockito.mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(401);
        verify(encoder).matches(eq(VALID_KEY), anyString());
    }

    @Test
    void repeatedFailuresFromOneClientAreThrottled() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++)
            invoke(gatewayRequest(application.getId().toString(), "jns_wrong"), Mockito.mock(FilterChain.class));

        var chain = Mockito.mock(FilterChain.class);
        var response = invoke(gatewayRequest(application.getId().toString(), VALID_KEY), chain);

        assertThat(response.getStatus()).isEqualTo(429);
        verify(chain, never()).doFilter(any(), any());
        // Blocked for fifteen minutes. A caller not told that reads this as a wrong key, and either
        // hunts for a fault that is not there or keeps retrying — which is what the block stops.
        assertThat(response.getHeader(org.springframework.http.HttpHeaders.RETRY_AFTER))
                .isNotNull()
                .satisfies(seconds -> assertThat(Long.parseLong(seconds)).isBetween(890L, 901L));
        assertThat(response.getHeader(io.janus.shared.ApiProblem.HEADER)).isEqualTo("authentication_throttled");
    }

    /** The two refusals are different problems, and only one of them is worth waiting out. */
    @Test
    void aRefusedKeyAndABlockedClientAreNamedApart() throws Exception {
        var refused =
                invoke(gatewayRequest(application.getId().toString(), "jns_wrong"), Mockito.mock(FilterChain.class));

        assertThat(refused.getStatus()).isEqualTo(401);
        assertThat(refused.getHeader(io.janus.shared.ApiProblem.HEADER)).isEqualTo("authentication_required");
        assertThat(refused.getContentAsString()).contains("\"code\":\"authentication_required\"");
        assertThat(refused.getHeader(org.springframework.http.HttpHeaders.RETRY_AFTER))
                .isNull();
    }

    @Test
    void everyRejectionIsAudited() throws Exception {
        invoke(gatewayRequest(application.getId().toString(), "jns_wrong"), Mockito.mock(FilterChain.class));
        verify(audit)
                .recordAuthenticationDenied(
                        eq(AuditActor.APPLICATION),
                        eq(AuditAction.GATEWAY_AUTHENTICATION),
                        anyString(),
                        anyString(),
                        eq(401),
                        anyString());
    }

    @Test
    void theRejectionBodyDoesNotDiscloseWhyAuthenticationFailed() throws Exception {
        var unknownApplication =
                invoke(gatewayRequest(UUID.randomUUID().toString(), VALID_KEY), Mockito.mock(FilterChain.class));
        var wrongKey =
                invoke(gatewayRequest(application.getId().toString(), "jns_wrong"), Mockito.mock(FilterChain.class));
        assertThat(detailOf(unknownApplication)).isEqualTo(detailOf(wrongKey));
    }

    /** Only the correlation identifier may differ between two rejections. */
    private static String detailOf(MockHttpServletResponse response) throws Exception {
        return new ObjectMapper()
                .readTree(response.getContentAsString())
                .get("detail")
                .asText();
    }

    @Test
    void theFilterIgnoresRequestsOutsideTheGateway() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/admin/applications");
        request.setRemoteAddr("203.0.113.10");
        var chain = Mockito.mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
        verifyNoInteractions(applications);
    }
}
