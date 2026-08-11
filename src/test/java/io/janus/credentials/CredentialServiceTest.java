package io.janus.credentials;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.janus.accounts.*;
import io.janus.audit.AuditService;
import io.janus.gateway.TrafficPolicyRegistry;
import io.janus.grants.GrantRepository;
import io.janus.openbao.OpenBaoClient;
import io.janus.providers.*;

/**
 * When OpenBao is written to, and when it is left alone.
 *
 * <p>An open API has no stored value, which is the whole of what {@code NONE} means. The assertions
 * that matter here are therefore about a collaborator that must <em>not</em> be called — and about
 * the two crossings, in and out of having a secret, where getting it wrong leaves either a
 * credential pointing at nothing or a secret nothing points at.
 */
class CredentialServiceTest {
    private final CredentialRepository repository = Mockito.mock(CredentialRepository.class);
    private final ProviderRepository providers = Mockito.mock(ProviderRepository.class);
    private final GrantRepository grants = Mockito.mock(GrantRepository.class);
    private final OpenBaoClient openBao = Mockito.mock(OpenBaoClient.class);
    private final TrafficPolicyRegistry traffic = Mockito.mock(TrafficPolicyRegistry.class);
    private final AccessScope scope = Mockito.mock(AccessScope.class);
    private final AuditService audit = Mockito.mock(AuditService.class);

    private final Account owner = TestAccount.owner();
    private final Provider provider = new Provider(
            owner, "PokéAPI", "pokeapi", "https://pokeapi.co", true, new Provider.TrafficPolicy(true, 0, 0, 0));

    private CredentialService service;

    @BeforeEach
    void setUp() {
        service = new CredentialService(
                repository, providers, grants, openBao, traffic, new DestinationValidator(false), scope, audit);
        when(scope.ownerFilter()).thenReturn(owner.getId());
        when(providers.findOwnedBy(provider.getId(), owner.getId())).thenReturn(Optional.of(provider));
        when(repository.save(any(Credential.class))).thenAnswer(invocation -> invocation.getArgument(0));
        // The service defers destruction to after the commit; nothing here runs in a transaction, so
        // the synchronizations it registers are collected by hand and run by runCommit below.
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private static void runCommit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
    }

    private CredentialRequest request(AuthType authType, String secret) {
        return new CredentialRequest(
                "pokeapi", provider.getId(), authType, null, null, null, null, null, secret, null, true);
    }

    private Credential existing(AuthType authType) {
        var credential = new Credential(provider, "pokeapi", Credential.Strategy.of(authType), null, true);
        when(repository.findOwnedBy(credential.getId(), owner.getId())).thenReturn(Optional.of(credential));
        return credential;
    }

    @Test
    void anOpenApiIsRegisteredWithoutAnythingReachingOpenBao() {
        var response = service.create(request(AuthType.NONE, null));

        verifyNoInteractions(openBao);
        assertThat(response.authType()).isEqualTo(AuthType.NONE);
        // Nothing lives at the path, so naming one would point an operator at an empty location.
        assertThat(response.secretRef()).isNull();
    }

    @Test
    void everyOtherStrategyStillRefusesToBeCreatedWithoutItsValue() {
        assertThatThrownBy(() -> service.create(request(AuthType.BEARER, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(openBao);
    }

    /** Nothing was ever stored at this path, so the edit that starts presenting something must say what. */
    @Test
    void givingAnOpenApiAStrategyNeedsTheValueItWillPresent() {
        var credential = existing(AuthType.NONE);

        assertThatThrownBy(() -> service.update(credential.getId(), request(AuthType.BEARER, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Secret is required");

        assertThatNoException()
                .isThrownBy(() -> service.update(credential.getId(), request(AuthType.BEARER, "sk_live_31337")));
        verify(openBao).write(credential.getSecretPath(), "sk_live_31337");
    }

    /** The other crossing: what nothing will read again is destroyed, not left behind in OpenBao. */
    @Test
    void takingTheStrategyAwayDestroysTheValueNothingWillReadAgain() {
        var credential = existing(AuthType.BEARER);

        service.update(credential.getId(), request(AuthType.NONE, null));
        verify(openBao, never()).delete(any());

        runCommit();
        verify(openBao).delete(credential.getSecretPath());
    }

    @Test
    void deletingAnOpenApiHasNothingToDestroy() {
        var credential = existing(AuthType.NONE);

        service.delete(credential.getId());
        runCommit();

        verify(repository).delete(credential);
        verifyNoInteractions(openBao);
    }
}
