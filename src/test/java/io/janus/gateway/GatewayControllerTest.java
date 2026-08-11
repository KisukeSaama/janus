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
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.ObjectMapper;

import io.janus.audit.AuditOutcome;
import io.janus.audit.AuditService;
import io.janus.credentials.TokenExchangeException;
import io.janus.grants.GrantRepository;
import io.janus.providers.DestinationValidator;
import io.janus.providers.ProviderRepository;
import io.janus.security.GatewayPrincipal;
import io.janus.shared.CorrelationIdFilter;
import io.janus.testing.Fixtures;

/**
 * The gateway's decisions about who may call what.
 *
 * <p>The outbound half is mocked throughout, which is the point: these tests state what must have
 * been settled <em>before</em> {@link GatewayTrafficService} is ever reached, and the strongest
 * assertion most of them make is that it was not reached at all. A refusal that still called it
 * would be a refusal that still read a secret out of OpenBao.
 */
class GatewayControllerTest {
    private final ProviderRepository providers = Mockito.mock(ProviderRepository.class);
    private final GrantRepository grants = Mockito.mock(GrantRepository.class);
    private final GatewayTrafficService traffic = Mockito.mock(GatewayTrafficService.class);
    private final AuditService audit = Mockito.mock(AuditService.class);
    private final GatewayMetrics metrics = Mockito.mock(GatewayMetrics.class);

    private final io.janus.accounts.Account owner = Fixtures.owner();
    private final io.janus.providers.Provider provider = Fixtures.provider(owner);
    private final io.janus.credentials.Credential credential = Fixtures.credential(provider);
    private final io.janus.applications.Application application = Fixtures.application(owner);
    private final io.janus.grants.Grant grant = Fixtures.grant(application, provider, credential);

    private final GatewayPrincipal principal =
            new GatewayPrincipal(application.getId(), application.getName(), owner.getId());

    private MockMvc mvc;
    private GatewayController controller;

    @BeforeEach
    void setUp() {
        controller = new GatewayController(
                providers, grants, new DestinationValidator(false), traffic, audit, metrics, new ObjectMapper());
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();

        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        when(providers.findBySlugAndOwnerIdAndEnabledTrue("spotify", owner.getId()))
                .thenReturn(Optional.of(provider));
        when(grants.findActive(application.getId(), provider.getId())).thenReturn(Optional.of(grant));
        when(traffic.forward(any())).thenReturn(anOutcome());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static GatewayOutcome anOutcome() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new GatewayOutcome(HttpStatus.OK, headers, "{\"ok\":true}".getBytes(), CacheStatus.MISS, "MISS");
    }

    /** What the audit trail was told about the call, which is the only record of a refusal. */
    private AuditService.GatewayEvent recordedEvent() {
        var captor = ArgumentCaptor.forClass(AuditService.GatewayEvent.class);
        verify(audit).recordGateway(captor.capture());
        return captor.getValue();
    }

    // --- the authorised path ------------------------------------------------

    @Test
    void relaysAnAuthorisedCallAndReturnsWhatTheProviderAnswered() throws Exception {
        mvc.perform(get("/gateway/spotify/v1/tracks"))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"ok\":true}"))
                .andExpect(header().exists(CorrelationIdFilter.RESPONSE_HEADER));

        assertThat(recordedEvent().outcome()).isEqualTo(AuditOutcome.SUCCESS);
    }

    @Test
    void forwardsTheProviderTheGrantAndTheDecodedPath() throws Exception {
        mvc.perform(get("/gateway/spotify/v1/tracks?market=FR"));

        var captor = ArgumentCaptor.forClass(GatewayExchange.class);
        verify(traffic).forward(captor.capture());
        var exchange = captor.getValue();
        assertThat(exchange.provider()).isSameAs(provider);
        assertThat(exchange.grant()).isSameAs(grant);
        assertThat(exchange.method()).isEqualTo(HttpMethod.GET);
        assertThat(exchange.route().decodedPath()).isEqualTo("/v1/tracks");
        assertThat(exchange.route().rawQuery()).isEqualTo("market=FR");
    }

    /**
     * A caller must not be able to shape what the upstream is told about who is calling, and the
     * gateway's own authentication material must not travel onward either.
     */
    @Test
    void doesNotForwardTheCallersHopOrItsCredentials() throws Exception {
        mvc.perform(get("/gateway/spotify/v1/tracks")
                .header(HttpHeaders.AUTHORIZATION, "Bearer caller-token")
                .header("X-Janus-Api-Key", "jns_secret")
                .header("X-Forwarded-For", "10.0.0.1")
                .header("Origin", "https://evil.example")
                .header("Cookie", "session=1")
                .header("Accept-Language", "fr-FR"));

        var captor = ArgumentCaptor.forClass(GatewayExchange.class);
        verify(traffic).forward(captor.capture());
        var forwarded = captor.getValue().headers();
        assertThat(forwarded.headerNames())
                .doesNotContainAnyElementsOf(
                        List.of("Authorization", "X-Janus-Api-Key", "X-Forwarded-For", "Origin", "Cookie"));
        assertThat(forwarded.getFirst("Accept-Language")).isEqualTo("fr-FR");
    }

    // --- refusals -----------------------------------------------------------

    /**
     * A slug names a destination inside its owner's namespace. Somebody else's is not a destination
     * here, and is refused with the same answer as one that was never registered — so the gateway
     * does not become a way to discover what other people have registered.
     */
    @Test
    void aSlugBelongingToSomebodyElseIsNotFound() throws Exception {
        when(providers.findBySlugAndOwnerIdAndEnabledTrue("spotify", owner.getId()))
                .thenReturn(Optional.empty());

        mvc.perform(get("/gateway/spotify/v1/tracks")).andExpect(status().isNotFound());
        verify(traffic, never()).forward(any());
    }

    @Test
    void aCallWithoutAnActiveGrantIsRefused() throws Exception {
        when(grants.findActive(application.getId(), provider.getId())).thenReturn(Optional.empty());

        mvc.perform(get("/gateway/spotify/v1/tracks")).andExpect(status().isForbidden());
        verify(traffic, never()).forward(any());
        assertThat(recordedEvent().outcome()).isEqualTo(AuditOutcome.DENIED);
    }

    /** A grant may stay in place while the secret behind it is withdrawn. */
    @Test
    void aDisabledCredentialStopsTheCallEvenWithAGrant() throws Exception {
        var withdrawn = Fixtures.provider(owner, "disabled");
        var disabled = new io.janus.credentials.Credential(
                withdrawn,
                "key",
                io.janus.credentials.Credential.Strategy.of(io.janus.credentials.AuthType.BEARER),
                null,
                false);
        var grantOnDisabled = Fixtures.grant(application, withdrawn, disabled);
        when(providers.findBySlugAndOwnerIdAndEnabledTrue("disabled", owner.getId()))
                .thenReturn(Optional.of(withdrawn));
        when(grants.findActive(application.getId(), withdrawn.getId())).thenReturn(Optional.of(grantOnDisabled));

        mvc.perform(get("/gateway/disabled/v1/tracks")).andExpect(status().isForbidden());
        verify(traffic, never()).forward(any());
    }

    /**
     * A grant admits a caller to a destination, not to a subset of it. Nothing about the path is
     * decided here, so a route nobody anticipated travels exactly like the one in the sample.
     */
    @Test
    void relaysAnyPathAndAnyMethodTheGrantWasNeverToldAbout() throws Exception {
        mvc.perform(post("/gateway/spotify/blabla/blabla/blabla")).andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(GatewayExchange.class);
        verify(traffic).forward(captor.capture());
        assertThat(captor.getValue().route().decodedPath()).isEqualTo("/blabla/blabla/blabla");
        assertThat(captor.getValue().method()).isEqualTo(HttpMethod.POST);
    }

    /**
     * TRACE in particular: proxying it hands a caller back whatever the hop injected on the way.
     *
     * <p>Called directly rather than through MockMvc because the servlet container answers TRACE and
     * OPTIONS itself and they never reach a handler. That is what makes this guard worth stating:
     * it is the one that still holds if the request ever does arrive.
     */
    @Test
    void aMethodTheGatewayDoesNotProxyIsRefusedBeforeAnythingIsLookedUp() {
        var request = new org.springframework.mock.web.MockHttpServletRequest("TRACE", "/gateway/spotify/v1/tracks");

        var response = controller.proxy("spotify", principal, request, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        verify(providers, never()).findBySlugAndOwnerIdAndEnabledTrue(any(), any());
        verify(traffic, never()).forward(any());
    }

    @Test
    void aTraversingPathIsRefused() throws Exception {
        mvc.perform(get("/gateway/spotify/v1/../admin")).andExpect(status().isBadRequest());
        verify(traffic, never()).forward(any());
    }

    // --- how failures are reported ------------------------------------------

    @Test
    void aRefusalIsWrittenAsAProblemDocumentCarryingTheCorrelationId() throws Exception {
        when(grants.findActive(application.getId(), provider.getId())).thenReturn(Optional.empty());

        mvc.perform(get("/gateway/spotify/v1/tracks"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.detail").value("No active grant for this provider"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void aThrottledCallIsAnsweredWithRetryAfter() throws Exception {
        when(traffic.forward(any())).thenThrow(new Throttled("Provider rate limit reached", 42, new HttpHeaders()));

        mvc.perform(get("/gateway/spotify/v1/tracks"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "42"));

        assertThat(recordedEvent().outcome()).isEqualTo(AuditOutcome.THROTTLED);
    }

    @Test
    void aDestinationThatResolvesSomewhereForbiddenIsABadGateway() throws Exception {
        when(traffic.forward(any()))
                .thenThrow(new GatewayHttpClientConfig.BlockedDestinationException("resolves to 127.0.0.1"));

        mvc.perform(get("/gateway/spotify/v1/tracks"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("Destination address is not permitted"));
    }

    /**
     * A token endpoint that refuses client credentials routinely quotes them back. Neither that
     * message nor an upstream body may reach the caller or the journal.
     */
    @Test
    void aFailedTokenExchangeNeverQuotesWhatTheTokenEndpointSaid() throws Exception {
        when(traffic.forward(any())).thenThrow(new TokenExchangeException("invalid_client: secret sk_live_31337"));

        mvc.perform(get("/gateway/spotify/v1/tracks"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("Could not obtain an access token for this credential"))
                .andExpect(
                        content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sk_live"))));
    }

    @Test
    void anUpstreamFailureIsReportedWithoutItsBody() throws Exception {
        when(traffic.forward(any()))
                .thenThrow(WebClientResponseException.create(
                        401,
                        "Unauthorized",
                        HttpHeaders.EMPTY,
                        "{\"error\":\"bad key sk_live_31337\"}".getBytes(),
                        null));

        mvc.perform(get("/gateway/spotify/v1/tracks"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("Upstream request failed"))
                .andExpect(
                        content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sk_live"))));

        assertThat(recordedEvent().detail()).isEqualTo("Upstream returned 401");
    }

    @Test
    void anUnexpectedFailureIsReportedWithoutItsMessage() throws Exception {
        when(traffic.forward(any())).thenThrow(new IllegalStateException("jdbc://user:hunter2@db/janus is down"));

        mvc.perform(get("/gateway/spotify/v1/tracks"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("Upstream request failed"))
                .andExpect(
                        content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("hunter2"))));

        assertThat(recordedEvent().outcome()).isEqualTo(AuditOutcome.ERROR);
    }

    // --- what is measured ---------------------------------------------------

    /**
     * Tagging the requested slug rather than the resolved one would let any caller mint an unbounded
     * number of time series just by inventing names.
     */
    @Test
    void doesNotTagMetricsWithASlugThatWasNeverResolved() throws Exception {
        when(providers.findBySlugAndOwnerIdAndEnabledTrue("invented", owner.getId()))
                .thenReturn(Optional.empty());

        mvc.perform(get("/gateway/invented/v1/tracks"));

        verify(metrics).record(isNull(), eq(AuditOutcome.DENIED), isNull(), eq(404), anyLong());
    }

    @Test
    void tagsMetricsWithTheProviderThatAnswered() throws Exception {
        mvc.perform(get("/gateway/spotify/v1/tracks"));

        verify(metrics).record(eq("spotify"), eq(AuditOutcome.SUCCESS), eq(CacheStatus.MISS), eq(200), anyLong());
    }
}
