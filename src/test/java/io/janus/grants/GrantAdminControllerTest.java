package io.janus.grants;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import io.janus.accounts.*;
import io.janus.applications.*;
import io.janus.audit.AuditService;
import io.janus.credentials.*;
import io.janus.gateway.TrafficPolicyRegistry;
import io.janus.providers.*;
import io.janus.shared.ApiExceptionHandler;

class GrantAdminControllerTest {
    private final GrantRepository grants = Mockito.mock(GrantRepository.class);
    private final ApplicationRepository applications = Mockito.mock(ApplicationRepository.class);
    private final ProviderRepository providers = Mockito.mock(ProviderRepository.class);
    private final CredentialRepository credentials = Mockito.mock(CredentialRepository.class);
    private final TrafficPolicyRegistry traffic = Mockito.mock(TrafficPolicyRegistry.class);
    private final AccessScope scope = Mockito.mock(AccessScope.class);
    private final AuditService audit = Mockito.mock(AuditService.class);

    private final Account owner = TestAccount.owner();
    private MockMvc mvc;
    private Application application;
    private Provider provider;
    private Credential credential;

    private Provider provider(String name, String slug) {
        return provider(owner, name, slug);
    }

    private static Provider provider(Account owner, String name, String slug) {
        return new Provider(
                owner, name, slug, "https://api.example.com", true, new Provider.TrafficPolicy(true, 0, 0, 0));
    }

    @BeforeEach
    void setUp() {
        var service = new GrantService(grants, applications, providers, credentials, traffic, scope, audit);
        mvc = MockMvcBuilders.standaloneSetup(new GrantAdminController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
        when(grants.save(any(Grant.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(scope.ownerFilter()).thenReturn(owner.getId());

        application = new Application(owner, "orders", null, true, "hash");
        provider = provider("Billing API", "billing");
        credential = new Credential(provider, "billing-key", Credential.Strategy.of(AuthType.BEARER), null, true);

        when(applications.findOwnedBy(application.getId(), owner.getId())).thenReturn(Optional.of(application));
        when(providers.findById(provider.getId())).thenReturn(Optional.of(provider));
        when(credentials.findOwnedBy(credential.getId(), owner.getId())).thenReturn(Optional.of(credential));
    }

    /**
     * The separation between people, checked where it is enforced: a rule cannot be written across
     * two registries even when both identifiers are known.
     */
    @Test
    void refusesToBindAServiceAndAnApiThatBelongToDifferentOwners() throws Exception {
        var stranger = TestAccount.owner("stranger");
        var theirs = provider(stranger, "Their API", "theirs");
        var theirCredential = new Credential(theirs, "their-key", Credential.Strategy.of(AuthType.BEARER), null, true);
        when(providers.findById(theirs.getId())).thenReturn(Optional.of(theirs));
        when(credentials.findOwnedBy(theirCredential.getId(), owner.getId())).thenReturn(Optional.of(theirCredential));

        mvc.perform(post("/api/admin/grants").contentType("application/json").content("""
                                {"applicationId":"%s","providerId":"%s","credentialId":"%s","enabled":true}""".formatted(
                                application.getId(), theirs.getId(), theirCredential.getId())))
                .andExpect(status().isBadRequest());
    }

    /** Somebody else's record is not a forbidden one; from where the caller stands it does not exist. */
    @Test
    void aRecordBelongingToSomebodyElseReadsAsNotFound() throws Exception {
        var unknown = UUID.randomUUID();
        when(applications.findOwnedBy(unknown, owner.getId())).thenReturn(Optional.empty());

        mvc.perform(post("/api/admin/grants").contentType("application/json").content("""
                                {"applicationId":"%s","providerId":"%s","credentialId":"%s","enabled":true}""".formatted(
                                unknown, provider.getId(), credential.getId())))
                .andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.ResultActions postGrant() throws Exception {
        return mvc.perform(
                post("/api/admin/grants").contentType("application/json").content("""
                        {"applicationId":"%s","providerId":"%s","credentialId":"%s","enabled":true}""".formatted(
                                application.getId(), provider.getId(), credential.getId())));
    }

    /**
     * Three identifiers are the whole request. Nothing states which paths the service may reach,
     * because a grant no longer decides that.
     */
    @Test
    void createsAGrantFromTheThreeRecordsItTiesTogether() throws Exception {
        postGrant()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationName").value("orders"))
                .andExpect(jsonPath("$.providerName").value("Billing API"))
                .andExpect(jsonPath("$.credentialName").value("billing-key"));
    }

    @Test
    void aCredentialFromAnotherProviderIsRefused() throws Exception {
        var otherProvider = provider("Other API", "other");
        when(credentials.findOwnedBy(credential.getId(), owner.getId()))
                .thenReturn(Optional.of(new Credential(
                        otherProvider, "other-key", Credential.Strategy.of(AuthType.BEARER), null, true)));

        postGrant().andExpect(status().isBadRequest());
    }

    @Test
    void anUnknownApplicationIsNotFound() throws Exception {
        when(applications.findOwnedBy(application.getId(), owner.getId())).thenReturn(Optional.empty());
        postGrant().andExpect(status().isNotFound());
    }

    /** A burst with nothing to burst against is a configuration mistake, not a permissive default. */
    @Test
    void refusesABurstWithoutAnAllowance() throws Exception {
        mvc.perform(post("/api/admin/grants").contentType("application/json").content("""
                                {"applicationId":"%s","providerId":"%s","credentialId":"%s","enabled":true,
                                 "rateLimitPerMinute":0,"rateLimitBurst":10}""".formatted(
                                application.getId(), provider.getId(), credential.getId())))
                .andExpect(status().isBadRequest());
    }
}
