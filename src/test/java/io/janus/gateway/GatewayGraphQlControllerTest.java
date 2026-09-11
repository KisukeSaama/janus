package io.janus.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;

import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import io.janus.audit.AuditOutcome;
import io.janus.audit.AuditService;
import io.janus.gateway.graphql.*;
import io.janus.grants.GrantRepository;
import io.janus.grants.GrantScope;
import io.janus.providers.DestinationValidator;
import io.janus.providers.Provider;
import io.janus.providers.ProviderRepository;
import io.janus.security.GatewayPrincipal;
import io.janus.testing.Fixtures;

/**
 * What the gateway decides about a GraphQL call before anything leaves: whether the grant admits the
 * operation, whether the document is one it can read, and where GraphQL may be sent at all.
 *
 * <p>The outbound half is mocked, as in {@link GatewayControllerTest}, and the strongest assertion
 * most refusals make is that it was never reached.
 */
class GatewayGraphQlControllerTest {
    private final ProviderRepository providers = Mockito.mock(ProviderRepository.class);
    private final GrantRepository grants = Mockito.mock(GrantRepository.class);
    private final GatewayTrafficService traffic = Mockito.mock(GatewayTrafficService.class);
    private final AuditService audit = Mockito.mock(AuditService.class);
    private final GatewayMetrics metrics = Mockito.mock(GatewayMetrics.class);
    private final AuthorizationCache authorizations = new AuthorizationCache(new GatewayTrafficProperties(
            new GatewayTrafficProperties.Cache(true, 100, 1_000_000, 10_000_000, 300),
            new GatewayTrafficProperties.Throttle(1, 300),
            new GatewayTrafficProperties.Retry(2, 1, 1),
            new GatewayTrafficProperties.Authorization(false, 10, 100),
            new GatewayTrafficProperties.Transform(true, 2097152)));

    private final io.janus.accounts.Account owner = Fixtures.owner();
    private final Provider provider = Fixtures.provider(owner, "github");
    private final io.janus.credentials.Credential credential = Fixtures.credential(provider);
    private final io.janus.applications.Application application = Fixtures.application(owner);
    private final io.janus.grants.Grant grant = Fixtures.grant(application, provider, credential);

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        provider.applyGraphQl(new Provider.GraphQl("/graphql", 5, 0));
        var graphql = GraphQlProperties.defaults();
        var controller = new GatewayController(
                new GatewayAdmission(providers, grants, authorizations, new DestinationValidator(false, false)),
                new GraphQlInspector(new ObjectMapper(), new PersistedQueries(graphql), graphql),
                new GraphQlStreams(graphql),
                graphql,
                traffic,
                audit,
                metrics,
                new ObjectMapper());
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        var principal = new GatewayPrincipal(
                application.getId(), application.getName(), owner.getId(), owner.getUsername(), Set.of());
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        when(providers.findBySlugAndEnabledTrue("github")).thenReturn(Optional.of(provider));
        when(grants.findActive(application.getId(), provider.getId())).thenReturn(Optional.of(grant));
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        when(traffic.forward(any()))
                .thenReturn(new GatewayOutcome(
                        HttpStatus.OK, headers, "{\"data\":{}}".getBytes(), CacheStatus.MISS, "MISS"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static String document(String query) {
        return "{\"query\":\"" + query.replace("\"", "\\\"") + "\"}";
    }

    private org.springframework.test.web.servlet.ResultActions send(String path, String query) throws Exception {
        return mvc.perform(post("/gateway/github" + path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(document(query)));
    }

    private GatewayExchange forwarded() {
        var captor = ArgumentCaptor.forClass(GatewayExchange.class);
        verify(traffic).forward(captor.capture());
        return captor.getValue();
    }

    private AuditService.GatewayEvent recorded() {
        var captor = ArgumentCaptor.forClass(AuditService.GatewayEvent.class);
        verify(audit).recordGateway(captor.capture());
        return captor.getValue();
    }

    private void readOnly() {
        grant.applyScope(GrantScope.of(null, null, true, "QUERY", null));
    }

    // --- what is forwarded ----------------------------------------------------

    @Test
    void forwardsAQueryAndSaysWhatItWas() throws Exception {
        send("/graphql", "query Viewer { viewer { login } }").andExpect(status().isOk());

        assertThat(forwarded().graphql().readsOnly()).isTrue();
        assertThat(recorded().detail()).startsWith("graphql query Viewer [viewer]");
    }

    @Test
    void readsTheEndpointWhateverCaseTheCallerSpelledItIn() throws Exception {
        send("/GraphQL/", "mutation { wipe }").andExpect(status().isOk());

        assertThat(forwarded().graphql().writes()).isTrue();
    }

    // --- what a grant admits --------------------------------------------------

    @Test
    void aReadOnlyGrantAdmitsAQuerySentAsThePostGraphQlRequires() throws Exception {
        readOnly();

        send("/graphql", "{ viewer { login } }").andExpect(status().isOk());
    }

    /** The one thing a read-only grant on a GraphQL API is for. */
    @Test
    void aReadOnlyGrantRefusesAMutationBeforeAnythingLeaves() throws Exception {
        readOnly();

        send("/graphql", "mutation { deleteRepository(id: 1) { ok } }")
                .andExpect(status().isForbidden())
                .andExpect(header().string("X-Janus-Error", "graphql_operation_not_granted"));

        verify(traffic, never()).forward(any());
        assertThat(recorded().outcome()).isEqualTo(AuditOutcome.DENIED);
    }

    /** Methods cannot tell reading from writing here; the operation types say it instead. */
    @Test
    void operationTypesStandInForMethodsAtTheEndpoint() throws Exception {
        grant.applyScope(GrantScope.of(null, "GET,HEAD", true, "QUERY", null));

        send("/graphql", "{ viewer { login } }").andExpect(status().isOk());
    }

    @Test
    void refusesARootFieldTheGrantDoesNotName() throws Exception {
        grant.applyScope(GrantScope.of(null, null, true, null, "viewer"));

        send("/graphql", "{ organization(login: \"acme\") { name } }")
                .andExpect(status().isForbidden())
                .andExpect(header().string("X-Janus-Error", "graphql_field_not_granted"));
        verify(traffic, never()).forward(any());
    }

    /** Sending the document to a second route the upstream also executes it on is the way around. */
    @Test
    void aNarrowedGrantRefusesGraphQlSentAnywhereElse() throws Exception {
        readOnly();

        send("/api/graphql", "mutation { wipe }")
                .andExpect(status().isForbidden())
                .andExpect(header().string("X-Janus-Error", "graphql_outside_endpoint"));
        verify(traffic, never()).forward(any());
    }

    @Test
    void aNarrowedGrantLeavesOrdinaryCallsElsewhereAlone() throws Exception {
        readOnly();

        mvc.perform(get("/gateway/github/repos/acme/janus")).andExpect(status().isOk());

        assertThat(forwarded().graphql()).isNull();
    }

    // --- what cannot be read --------------------------------------------------

    @Test
    void refusesADocumentItCannotRead() throws Exception {
        send("/graphql", "{ viewer ")
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Janus-Error", "graphql_invalid"));
        verify(traffic, never()).forward(any());
    }

    @Test
    void refusesADocumentDeeperThanTheDestinationAccepts() throws Exception {
        send("/graphql", "{ a { b { c { d { e { f } } } } } }")
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Janus-Error", "graphql_too_complex"));
    }

    /** An APQ client resends the document when told the hash is unknown; that is the protocol working. */
    @Test
    void asksForTheDocumentBehindAnUnknownPersistedQuery() throws Exception {
        readOnly();

        mvc.perform(post("/gateway/github/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"extensions\":{\"persistedQuery\":{\"version\":1,\"sha256Hash\":\"abc\"}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.code").value("PERSISTED_QUERY_NOT_FOUND"));
        verify(traffic, never()).forward(any());
    }
}
