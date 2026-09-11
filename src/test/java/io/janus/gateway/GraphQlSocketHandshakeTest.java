package io.janus.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.*;

import org.junit.jupiter.api.*;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

import io.janus.audit.AuditService;
import io.janus.gateway.graphql.GraphQlProperties;
import io.janus.grants.GrantRepository;
import io.janus.grants.GrantScope;
import io.janus.providers.DestinationValidator;
import io.janus.providers.Provider;
import io.janus.providers.ProviderRepository;
import io.janus.security.GatewayPrincipal;
import io.janus.testing.Fixtures;

/**
 * A WebSocket is authorised before it exists, by the same steps as a request, and refused the same
 * way: a problem document and a code, not a socket that opens and then closes.
 */
class GraphQlSocketHandshakeTest {
    private final ProviderRepository providers = mock(ProviderRepository.class);
    private final GrantRepository grants = mock(GrantRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final GraphQlStreams streams = new GraphQlStreams(new GraphQlProperties(300, 0, 1, 100, 100, 25));
    private final AuthorizationCache authorizations = new AuthorizationCache(new GatewayTrafficProperties(
            new GatewayTrafficProperties.Cache(true, 100, 1_000_000, 10_000_000, 300),
            new GatewayTrafficProperties.Throttle(1, 300),
            new GatewayTrafficProperties.Retry(2, 1, 1),
            new GatewayTrafficProperties.Authorization(false, 10, 100),
            new GatewayTrafficProperties.Transform(true, 2097152)));
    private final GraphQlSocketHandshake handshake = new GraphQlSocketHandshake(
            new GatewayAdmission(providers, grants, authorizations, new DestinationValidator(false, false)),
            streams,
            audit,
            new ObjectMapper());

    private final io.janus.accounts.Account owner = Fixtures.owner();
    private final Provider provider = Fixtures.provider(owner, "github");
    private final io.janus.applications.Application application = Fixtures.application(owner);
    private final io.janus.grants.Grant grant = Fixtures.grant(application, provider, Fixtures.credential(provider));

    private MockHttpServletRequest request;
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final Map<String, Object> attributes = new HashMap<>();

    @BeforeEach
    void setUp() {
        provider.applyGraphQl(new Provider.GraphQl("/graphql", 0, 0));
        when(providers.findBySlugAndEnabledTrue("github")).thenReturn(Optional.of(provider));
        when(grants.findActive(application.getId(), provider.getId())).thenReturn(Optional.of(grant));
        var principal = new GatewayPrincipal(application.getId(), "checkout", owner.getId(), Set.of());
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private boolean shake(String path) {
        request = new MockHttpServletRequest("GET", path);
        var answer = new ServletServerHttpResponse(response);
        boolean admitted = handshake.beforeHandshake(new ServletServerHttpRequest(request), answer, null, attributes);
        try {
            answer.flush();
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
        return admitted;
    }

    private void finish(Exception failure) {
        handshake.afterHandshake(
                new ServletServerHttpRequest(request), new ServletServerHttpResponse(response), null, failure);
    }

    @Test
    void admitsAHandshakeToTheGraphQlEndpointAndHoldsItsPlace() {
        assertThat(shake("/gateway/github/graphql")).isTrue();

        assertThat(attributes).containsKey(GraphQlSocket.ATTRIBUTE);
        assertThat(streams.active()).isEqualTo(1);
    }

    @Test
    void refusesAHandshakeNobodyAuthenticated() {
        SecurityContextHolder.clearContext();

        assertThat(shake("/gateway/github/graphql")).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    /** Only GraphQL is relayed over a socket; anything else would be a tunnel nothing is decided on. */
    @Test
    void refusesAHandshakeAnywhereButTheEndpoint() {
        assertThat(shake("/gateway/github/repos")).isFalse();

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getHeader("X-Janus-Error")).isEqualTo("bad_request");
    }

    @Test
    void refusesAHandshakeWithoutAGrant() {
        when(grants.findActive(application.getId(), provider.getId())).thenReturn(Optional.empty());

        assertThat(shake("/gateway/github/graphql")).isFalse();
        assertThat(response.getHeader("X-Janus-Error")).isEqualTo("grant_missing");
        verify(audit).recordGateway(any());
    }

    @Test
    void refusesAHandshakeTheGrantsMethodsDoNotAdmit() {
        grant.applyScope(GrantScope.of(null, "POST", true));

        assertThat(shake("/gateway/github/graphql")).isFalse();
        assertThat(response.getHeader("X-Janus-Error")).isEqualTo("method_not_granted");
    }

    /** Where operation types are named, they decide, one operation at a time, as for a request. */
    @Test
    void leavesTheDecisionToOperationTypesWhereTheGrantNamesThem() {
        grant.applyScope(GrantScope.of(null, "POST", true, "SUBSCRIPTION", null));

        assertThat(shake("/gateway/github/graphql")).isTrue();
    }

    @Test
    void refusesAnApplicationOverItsShareOfStreams() {
        shake("/gateway/github/graphql");

        assertThat(shake("/gateway/github/graphql")).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("X-Janus-Error")).isEqualTo("stream_limit");
        assertThat(response.getHeader("Retry-After")).isNotNull();
    }

    @Test
    void givesThePlaceBackWhenTheUpgradeFails() {
        shake("/gateway/github/graphql");

        finish(new IllegalStateException("upgrade failed"));

        assertThat(streams.active()).isZero();
    }

    @Test
    void givesThePlaceBackWhenTheUpgradeWasRefused() {
        shake("/gateway/github/graphql");
        response.setStatus(400);

        finish(null);

        assertThat(streams.active()).isZero();
    }

    @Test
    void keepsThePlaceOfASocketThatOpened() {
        shake("/gateway/github/graphql");
        response.setStatus(101);

        finish(null);

        assertThat(streams.active()).isEqualTo(1);
    }
}
