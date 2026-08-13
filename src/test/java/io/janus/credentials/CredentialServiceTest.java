package io.janus.credentials;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.*;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import io.janus.accounts.*;
import io.janus.audit.AuditService;
import io.janus.gateway.TrafficPolicyRegistry;
import io.janus.grants.GrantRepository;
import io.janus.openbao.OpenBaoClient;
import io.janus.openbao.SecretDeletionQueue;
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
    private final SecretDeletionQueue secretDeletions = Mockito.mock(SecretDeletionQueue.class);
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
                repository,
                providers,
                grants,
                openBao,
                secretDeletions,
                traffic,
                new DestinationValidator(false, false),
                scope,
                audit);
        when(scope.ownerFilter()).thenReturn(owner.getId());
        when(scope.accountId()).thenReturn(owner.getId());
        when(providers.findById(provider.getId())).thenReturn(Optional.of(provider));
        when(repository.save(any(Credential.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private CredentialRequest request(AuthType authType, String secret) {
        return new CredentialRequest(
                "pokeapi", provider.getId(), authType, null, null, null, null, null, secret, null, true);
    }

    private Credential existing(AuthType authType) {
        provider.applyAuth(new Provider.Auth(authType, null, null, null, null, null));
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
        provider.applyAuth(new Provider.Auth(AuthType.BEARER, null, null, null, null, null));
        assertThatThrownBy(() -> service.create(request(AuthType.BEARER, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(openBao);
    }

    /** The API contract, not a user's credential payload, decides how authentication is presented. */
    @Test
    void aCredentialRequestCannotChangeTheApisStrategy() {
        var credential = existing(AuthType.NONE);

        var response = service.update(credential.getId(), request(AuthType.BEARER, null));

        assertThat(response.authType()).isEqualTo(AuthType.NONE);
        verifyNoInteractions(openBao);
    }

    /** An administrator changing the contract disables the activation until a new value is supplied. */
    @Test
    void anAuthenticationChangeRequiresReprovisioning() {
        var credential = existing(AuthType.NONE);
        provider.applyAuth(new Provider.Auth(AuthType.BEARER, null, null, null, null, null));
        credential.adoptProviderStrategy(AuthType.NONE);

        assertThatThrownBy(() -> service.update(credential.getId(), request(AuthType.NONE, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("New credentials are required");

        service.update(credential.getId(), request(AuthType.NONE, "sk_live_31337"));
        verify(openBao).write(credential.getSecretPath(), "sk_live_31337");
    }

    @Test
    void deletingAnOpenApiHasNothingToDestroy() {
        var credential = existing(AuthType.NONE);

        service.delete(credential.getId());

        verify(repository).delete(credential);
        verifyNoInteractions(openBao);
        verifyNoInteractions(secretDeletions);
    }

    @Test
    void deletingAStoredCredentialQueuesItsValueForDestruction() {
        var credential = existing(AuthType.BEARER);

        service.delete(credential.getId());

        verify(repository).delete(credential);
        verify(secretDeletions).enqueue(credential.getSecretPath());
    }

    /**
     * The connections go through the session, not in one bulk statement. A batch delete leaves them
     * managed and still pointing at the credential removed right after, and the flush at commit
     * refuses that as an HTTP 500 the console can say nothing useful about.
     */
    @Test
    void deletingACredentialRemovesItsConnectionsBeforeItself() {
        var credential = existing(AuthType.BEARER);
        var grant = Mockito.mock(io.janus.grants.Grant.class);
        when(grant.getId()).thenReturn(UUID.randomUUID());
        when(grants.findAllByCredentialId(credential.getId())).thenReturn(List.of(grant));

        service.delete(credential.getId());

        var order = inOrder(grants, repository);
        order.verify(grants).deleteAll(List.of(grant));
        order.verify(repository).delete(credential);
        verify(grants, never()).deleteAllInBatch(any());
    }
}
