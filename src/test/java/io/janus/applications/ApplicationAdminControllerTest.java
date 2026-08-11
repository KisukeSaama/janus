package io.janus.applications;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import io.janus.accounts.*;
import io.janus.audit.AuditService;
import io.janus.oauth.AccessTokenStore;
import io.janus.oauth.RefreshTokenRepository;
import io.janus.security.ApiKeyCache;
import io.janus.security.ApiKeyGenerator;
import io.janus.shared.ApiExceptionHandler;

class ApplicationAdminControllerTest {
    private final ApplicationRepository repository = Mockito.mock(ApplicationRepository.class);
    private final ApiKeyCache keyCache = Mockito.mock(ApiKeyCache.class);
    private final AccessTokenStore accessTokens = Mockito.mock(AccessTokenStore.class);
    private final RefreshTokenRepository refreshTokens = Mockito.mock(RefreshTokenRepository.class);
    private final AccountRepository accounts = Mockito.mock(AccountRepository.class);
    private final AccessScope scope = Mockito.mock(AccessScope.class);
    private final AuditService audit = Mockito.mock(AuditService.class);

    private final Account owner = TestAccount.owner();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var service = new ApplicationService(
                repository,
                new ApiKeyGenerator(new BCryptPasswordEncoder(4)),
                keyCache,
                accessTokens,
                refreshTokens,
                accounts,
                scope,
                audit);
        mvc = MockMvcBuilders.standaloneSetup(new ApplicationAdminController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
        when(repository.save(any(Application.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(scope.ownerFilter()).thenReturn(owner.getId());
        when(accounts.getReferenceById(owner.getId())).thenReturn(owner);
    }

    private Application application() {
        return new Application(owner, "orders", "Orders service", true, "previous-hash");
    }

    /** Registers an existing record as visible to the caller, the way a scoped finder would. */
    private void visible(Application application) {
        when(repository.findOwnedBy(application.getId(), owner.getId())).thenReturn(Optional.of(application));
    }

    @Test
    void issuesAOneTimeKeyAndNeverReturnsItsHash() throws Exception {
        mvc.perform(
                        post("/api/admin/applications")
                                .contentType("application/json")
                                .content(
                                        """
                                 {"name":"orders","description":"Orders service","enabled":true}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKey").value(org.hamcrest.Matchers.startsWith("jns_")))
                .andExpect(jsonPath("$.application.name").value("orders"))
                .andExpect(content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("apiKeyHash"))));
    }

    /** Registration time is answered straight away rather than only after the row has been flushed. */
    @Test
    void theResponseToACreateCarriesItsTimestamps() throws Exception {
        mvc.perform(post("/api/admin/applications")
                        .contentType("application/json")
                        .content("""
                                 {"name":"orders","enabled":true}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application.createdAt").exists())
                .andExpect(jsonPath("$.application.apiKeyRotatedAt").exists());
    }

    @Test
    void aBlankNameIsRejectedWithFieldLevelDetail() throws Exception {
        mvc.perform(post("/api/admin/applications")
                        .contentType("application/json")
                        .content("""
                                 {"name":"  ","enabled":true}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists());
    }

    @Test
    void malformedJsonIsAClientError() throws Exception {
        mvc.perform(post("/api/admin/applications")
                        .contentType("application/json")
                        .content("{"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUnusableIdentifierIsAClientErrorRatherThanAServerError() throws Exception {
        mvc.perform(delete("/api/admin/applications/not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    void deletingAnUnknownApplicationIsNotFound() throws Exception {
        var id = UUID.randomUUID();
        when(repository.findOwnedBy(id, owner.getId())).thenReturn(Optional.empty());
        mvc.perform(delete("/api/admin/applications/" + id)).andExpect(status().isNotFound());
    }

    /**
     * Somebody else's service is not a forbidden one; from where the caller stands it does not
     * exist. A 403 would confirm the identifier, which is an enumeration oracle.
     */
    @Test
    void aServiceBelongingToSomebodyElseReadsAsNotFoundRatherThanForbidden() throws Exception {
        var theirs = new Application(TestAccount.owner("stranger"), "theirs", null, true, "hash");
        when(repository.findOwnedBy(theirs.getId(), owner.getId())).thenReturn(Optional.empty());

        mvc.perform(delete("/api/admin/applications/" + theirs.getId())).andExpect(status().isNotFound());
        mvc.perform(put("/api/admin/applications/" + theirs.getId())
                        .contentType("application/json")
                        .content("""
                                 {"name":"stolen","enabled":true}"""))
                .andExpect(status().isNotFound());
        verify(repository, never()).delete(any());
    }

    /** The listing takes an owner, so it cannot answer with a registry the caller does not hold. */
    @Test
    void theListingIsAlwaysScopedToTheCaller() throws Exception {
        when(repository.findAllOwnedBy(owner.getId())).thenReturn(java.util.List.of(application()));

        mvc.perform(get("/api/admin/applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        verify(repository).findAllOwnedBy(owner.getId());
    }

    @Test
    void rotatingAKeyIssuesANewOneAndDropsTheCachedCredential() throws Exception {
        var application = application();
        visible(application);

        mvc.perform(post("/api/admin/applications/" + application.getId() + "/rotate-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKey").value(org.hamcrest.Matchers.startsWith("jns_")));

        org.assertj.core.api.Assertions.assertThat(application.getApiKeyHash()).isNotEqualTo("previous-hash");
        verify(keyCache).invalidate(application.getId());
    }

    /** Editing an identity must drop its cached key, or a disabling waits for the entry to expire. */
    @Test
    void updatingAnApplicationDropsTheCachedCredential() throws Exception {
        var application = application();
        visible(application);

        mvc.perform(put("/api/admin/applications/" + application.getId())
                        .contentType("application/json")
                        .content("""
                                 {"name":"orders","enabled":false}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        verify(keyCache).invalidate(application.getId());
    }
}
