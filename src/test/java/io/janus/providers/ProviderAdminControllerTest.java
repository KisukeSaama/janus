package io.janus.providers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import io.janus.accounts.AccessScope;
import io.janus.accounts.Account;
import io.janus.accounts.AccountRepository;
import io.janus.accounts.TestAccount;
import io.janus.audit.AuditAction;
import io.janus.audit.AuditService;
import io.janus.credentials.CredentialRepository;
import io.janus.gateway.TrafficPolicyRegistry;
import io.janus.grants.GrantRepository;
import io.janus.openbao.SecretDeletionQueue;
import io.janus.shared.ApiExceptionHandler;

/**
 * The registry of destinations, exercised through the surface an administrator actually uses.
 *
 * <p>Two rules carry most of the weight here: the catalogue is global, and every write has to reach
 * the running gateway. A policy that takes effect in five minutes is not a policy.
 */
class ProviderAdminControllerTest {
    private final ProviderRepository repository = Mockito.mock(ProviderRepository.class);
    private final CredentialRepository credentials = Mockito.mock(CredentialRepository.class);
    private final GrantRepository grants = Mockito.mock(GrantRepository.class);
    private final SecretDeletionQueue secretDeletions = Mockito.mock(SecretDeletionQueue.class);
    private final UpstreamPing ping = Mockito.mock(UpstreamPing.class);
    private final TrafficPolicyRegistry traffic = Mockito.mock(TrafficPolicyRegistry.class);
    private final AccountRepository accounts = Mockito.mock(AccountRepository.class);
    private final AccessScope scope = Mockito.mock(AccessScope.class);
    private final AuditService audit = Mockito.mock(AuditService.class);

    private final Account owner = TestAccount.owner();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // Private destinations allowed, which is what stops the validator from resolving the host:
        // an ordinary test must not need working DNS, and what it asserts here is the registry's
        // behaviour rather than the network's. The SSRF rules get their own MockMvc below.
        mvc = mvcValidatingWith(new DestinationValidator(true, false));

        when(scope.ownerFilter()).thenReturn(owner.getId());
        when(scope.accountId()).thenReturn(owner.getId());
        when(accounts.getReferenceById(owner.getId())).thenReturn(owner);
        when(repository.save(any(Provider.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private MockMvc mvcValidatingWith(DestinationValidator destinations) {
        var service = new ProviderService(
                repository, credentials, grants, secretDeletions, destinations, ping, traffic, accounts, scope, audit);
        return MockMvcBuilders.standaloneSetup(new ProviderAdminController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static String body(String name, String slug, String baseUrl) {
        return """
               {"name":"%s","slug":"%s","baseUrl":"%s","enabled":true,"authType":"NONE"}"""
                .formatted(name, slug, baseUrl);
    }

    private Provider existing() {
        var provider = new Provider(
                owner,
                "Spotify",
                "spotify",
                "https://api.spotify.com",
                true,
                new Provider.TrafficPolicy(true, 0, 0, 0));
        when(repository.findById(provider.getId())).thenReturn(Optional.of(provider));
        return provider;
    }

    // --- registering ---------------------------------------------------------

    @Test
    void registersADestination() throws Exception {
        mvc.perform(post("/api/admin/providers")
                        .contentType("application/json")
                        .content(body("Spotify", "spotify", "https://api.spotify.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("spotify"));

        verify(audit).recordAdmin(eq(AuditAction.PROVIDER_CREATED), any(), eq("spotify"));
    }

    /** A slug identifies one shared catalogue entry across the deployment. */
    @Test
    void refusesASlugTheSamePersonAlreadyUses() throws Exception {
        when(repository.existsBySlug("spotify")).thenReturn(true);

        mvc.perform(post("/api/admin/providers")
                        .contentType("application/json")
                        .content(body("Spotify", "spotify", "https://api.spotify.com")))
                .andExpect(status().isBadRequest());
        verify(repository, never()).save(any());
    }

    @Test
    void storesTheDestinationWithoutItsTrailingSlash() throws Exception {
        mvc.perform(post("/api/admin/providers")
                        .contentType("application/json")
                        .content(body("Spotify", "spotify", "https://api.spotify.com/v1/")))
                .andExpect(jsonPath("$.baseUrl").value("https://api.spotify.com/v1"));
    }

    /**
     * The gateway builds every target URI on the stored destination, so one that could reach inside
     * the deployment is refused where it is written rather than where it is used. None of these
     * needs a resolver: a loopback name resolves locally and the rest are refused on their shape.
     */
    @Test
    void refusesADestinationThatIsNotAPublicHttpsUrl() throws Exception {
        var strict = mvcValidatingWith(new DestinationValidator(false, false));

        for (String url : List.of(
                "http://api.spotify.com",
                "https://localhost/v1",
                "https://127.0.0.1/v1",
                "not-a-url",
                "https://user:pass@api.spotify.com",
                "https://api.spotify.com/../x")) {
            strict.perform(post("/api/admin/providers")
                            .contentType("application/json")
                            .content(body("Spotify", "spotify", url)))
                    .andExpect(status().isBadRequest());
        }
        verify(repository, never()).save(any());
    }

    @Test
    void refusesASlugThatIsNotInTheAcceptedShape() throws Exception {
        mvc.perform(post("/api/admin/providers")
                        .contentType("application/json")
                        .content(body("Spotify", "Spotify Web API", "https://api.spotify.com")))
                .andExpect(status().isBadRequest());
    }

    /** An unstated traffic policy takes the documented default rather than zero. */
    @Test
    void appliesTheDocumentedDefaultsWhenNoTrafficPolicyIsStated() throws Exception {
        mvc.perform(post("/api/admin/providers")
                        .contentType("application/json")
                        .content(body("Spotify", "spotify", "https://api.spotify.com")))
                .andExpect(jsonPath("$.cacheEnabled").value(true))
                .andExpect(jsonPath("$.cacheTtlSeconds").value(0))
                .andExpect(jsonPath("$.rateLimitPerMinute").value(0));
    }

    // --- changing and removing -----------------------------------------------

    @Test
    void anEditDropsWhatTheGatewayHoldsForThatDestination() throws Exception {
        var provider = existing();

        mvc.perform(put("/api/admin/providers/" + provider.getId())
                        .contentType("application/json")
                        .content(body("Spotify", "spotify", "https://api.other.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUrl").value("https://api.other.com"));

        verify(traffic).forgetProvider(provider.getId());
        verify(audit).recordAdmin(eq(AuditAction.PROVIDER_UPDATED), any(), eq("spotify"));
    }

    /** Renaming to a slug nobody else of yours uses is allowed; keeping your own is not a conflict. */
    @Test
    void anEditThatKeepsTheSameSlugIsNotAConflictWithItself() throws Exception {
        var provider = existing();
        when(repository.existsBySlug("spotify")).thenReturn(true);

        mvc.perform(put("/api/admin/providers/" + provider.getId())
                        .contentType("application/json")
                        .content(body("Spotify", "spotify", "https://api.spotify.com")))
                .andExpect(status().isOk());
    }

    @Test
    void refusesToRenameOntoASlugAlreadyInUse() throws Exception {
        var provider = existing();
        when(repository.existsBySlug("deezer")).thenReturn(true);

        mvc.perform(put("/api/admin/providers/" + provider.getId())
                        .contentType("application/json")
                        .content(body("Deezer", "deezer", "https://api.deezer.com")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void removesADestinationAndWhatTheGatewayHeldForIt() throws Exception {
        var provider = existing();

        mvc.perform(delete("/api/admin/providers/" + provider.getId())).andExpect(status().isOk());

        verify(repository).delete(provider);
        verify(traffic).forgetProvider(provider.getId());
    }

    /**
     * Removing an API removes its dependent records instead of leaving its slug occupied — and
     * removes them through the session, in dependency order. A bulk statement would leave the grants
     * this method just read still managed and still pointing at a removed provider, which Hibernate
     * refuses at commit rather than at the call, so it reaches the operator as an opaque HTTP 500.
     */
    @Test
    void removesConnectionsAndCredentialMetadataWithTheDestination() throws Exception {
        var provider = existing();
        var grant = Mockito.mock(io.janus.grants.Grant.class);
        var credential = Mockito.mock(io.janus.credentials.Credential.class);
        when(grant.getId()).thenReturn(UUID.randomUUID());
        when(credential.getId()).thenReturn(UUID.randomUUID());
        when(credential.getAuthType()).thenReturn(io.janus.credentials.AuthType.NONE);
        when(grants.findAllByProviderId(provider.getId())).thenReturn(List.of(grant));
        when(credentials.findAllByProviderId(provider.getId())).thenReturn(List.of(credential));

        mvc.perform(delete("/api/admin/providers/" + provider.getId())).andExpect(status().isOk());

        var order = inOrder(grants, credentials, repository);
        order.verify(grants).deleteAll(List.of(grant));
        order.verify(credentials).deleteAll(List.of(credential));
        order.verify(repository).delete(provider);
        verify(grants, never()).deleteAllInBatch(any());
        verify(credentials, never()).deleteAllInBatch(any());
    }

    /** The durable cleanup request is committed with the metadata deletion. */
    @Test
    void queuesStoredValuesForDestructionWhenRemovingAnApi() throws Exception {
        var provider = existing();
        var credential = Mockito.mock(io.janus.credentials.Credential.class);
        when(credential.getId()).thenReturn(UUID.randomUUID());
        when(credential.getAuthType()).thenReturn(io.janus.credentials.AuthType.BEARER);
        when(credential.getSecretPath()).thenReturn("janus/tmdb/credential");
        when(credentials.findAllByProviderId(provider.getId())).thenReturn(List.of(credential));

        mvc.perform(delete("/api/admin/providers/" + provider.getId())).andExpect(status().isOk());

        verify(secretDeletions).enqueueAll(List.of("janus/tmdb/credential"));
    }

    @Test
    void purgingReportsHowMuchWasDropped() throws Exception {
        var provider = existing();
        when(traffic.forgetProvider(provider.getId())).thenReturn(17);

        mvc.perform(delete("/api/admin/providers/" + provider.getId() + "/cache"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purged").value(17));
    }

    // --- is it still there ----------------------------------------------------

    /**
     * The probe reports what the destination answered, and a refusal is still an answer: an API that
     * says 401 to an unauthenticated HEAD is running, which is exactly what the question was.
     */
    @Test
    void reportsWhatTheDestinationAnswered() throws Exception {
        var provider = existing();
        when(ping.reach("https://api.spotify.com", false)).thenReturn(ProviderPing.answered(401, 42));

        mvc.perform(post("/api/admin/providers/" + provider.getId() + "/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reachable").value(true))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.reason").value("ANSWERED"));
    }

    /** A destination that cannot be reached names its cause rather than failing the request. */
    @Test
    void namesWhyADestinationCouldNotBeReached() throws Exception {
        var provider = existing();
        when(ping.reach("https://api.spotify.com", false))
                .thenReturn(ProviderPing.failed(ProviderPing.Reason.TIMED_OUT, 5000));

        mvc.perform(post("/api/admin/providers/" + provider.getId() + "/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reachable").value(false))
                .andExpect(jsonPath("$.status").value(0))
                .andExpect(jsonPath("$.reason").value("TIMED_OUT"));
    }

    /** Nothing leaves the process for an identifier that names no catalogue entry. */
    @Test
    void doesNotProbeADestinationThatDoesNotExist() throws Exception {
        var id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        mvc.perform(post("/api/admin/providers/" + id + "/ping")).andExpect(status().isNotFound());
        verifyNoInteractions(ping);
    }

    // --- the shared catalogue -------------------------------------------------

    @Test
    void listsTheSharedCatalogueAsAPage() throws Exception {
        var provider = existing();
        when(repository.search(eq(""), any())).thenReturn(new PageImpl<>(List.of(provider)));
        when(credentials.findActivatedProviderIds(owner.getId(), List.of(provider.getId())))
                .thenReturn(Set.of(provider.getId()));

        mvc.perform(get("/api/admin/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].activated").value(true));

        verify(repository).search(eq(""), any());
    }

    /**
     * Somebody else's destination answers "not found" rather than "not allowed": a 403 would confirm
     * the identifier exists, which is an enumeration oracle.
     */
    @Test
    void somebodyElsesDestinationIsNotFoundRatherThanForbidden() throws Exception {
        var id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        mvc.perform(delete("/api/admin/providers/" + id)).andExpect(status().isNotFound());
        mvc.perform(put("/api/admin/providers/" + id)
                        .contentType("application/json")
                        .content(body("Spotify", "spotify", "https://api.spotify.com")))
                .andExpect(status().isNotFound());
    }

    @Test
    void aBurstWithoutAnAllowanceIsRefused() throws Exception {
        mvc.perform(
                        post("/api/admin/providers")
                                .contentType("application/json")
                                .content(
                                        """
                                {"name":"Spotify","slug":"spotify","baseUrl":"https://api.spotify.com","enabled":true,"authType":"NONE","rateLimitBurst":10}"""))
                .andExpect(status().isBadRequest());
        verify(repository, never()).save(any());
    }

    @Test
    void trimsSurroundingSpaceOutOfAName() throws Exception {
        mvc.perform(post("/api/admin/providers")
                        .contentType("application/json")
                        .content(body("  Spotify  ", "spotify", "https://api.spotify.com")))
                .andExpect(jsonPath("$.name").value("Spotify"));
    }

    @Test
    void aBurstWithinAnAllowanceIsAccepted() throws Exception {
        mvc.perform(
                        post("/api/admin/providers")
                                .contentType("application/json")
                                .content(
                                        """
                                {"name":"Spotify","slug":"spotify","baseUrl":"https://api.spotify.com","enabled":true,"authType":"NONE","rateLimitPerMinute":600,"rateLimitBurst":60}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rateLimitBurst").value(60));
    }
}
