package io.janus.credentials;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.janus.accounts.AccessScope;
import io.janus.audit.AuditAction;
import io.janus.audit.AuditService;
import io.janus.gateway.TrafficPolicyRegistry;
import io.janus.grants.GrantRepository;
import io.janus.openbao.OpenBaoClient;
import io.janus.openbao.SecretDeletionQueue;
import io.janus.providers.DestinationValidator;
import io.janus.providers.Provider;
import io.janus.providers.ProviderRepository;
import io.janus.shared.NotFoundException;

/**
 * The metadata half of a secret. The value itself only ever travels from a request straight to
 * OpenBao, and nothing here can read it back.
 */
@Service
public class CredentialService {
    private final CredentialRepository repository;
    private final ProviderRepository providers;
    private final GrantRepository grants;
    private final OpenBaoClient openBao;
    private final SecretDeletionQueue secretDeletions;
    private final TrafficPolicyRegistry traffic;
    private final DestinationValidator destinations;
    private final AccessScope scope;
    private final AuditService audit;

    public CredentialService(
            CredentialRepository repository,
            ProviderRepository providers,
            GrantRepository grants,
            OpenBaoClient openBao,
            SecretDeletionQueue secretDeletions,
            TrafficPolicyRegistry traffic,
            DestinationValidator destinations,
            AccessScope scope,
            AuditService audit) {
        this.repository = repository;
        this.providers = providers;
        this.grants = grants;
        this.openBao = openBao;
        this.secretDeletions = secretDeletions;
        this.traffic = traffic;
        this.destinations = destinations;
        this.scope = scope;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<CredentialResponse> list() {
        return repository.findAllOwnedBy(scope.ownerFilter()).stream()
                .map(CredentialResponse::of)
                .toList();
    }

    @Transactional
    public CredentialResponse create(CredentialRequest request) {
        var provider = provider(request.providerId());
        if (repository
                .findByProviderIdAndOwnerId(provider.getId(), scope.accountId())
                .isPresent()) throw new IllegalArgumentException("This API is already active on your account");
        validateSecret(provider, request);
        if (!request.carriesSecret() && !provider.getAuthType().anonymous())
            throw new IllegalArgumentException("Credentials are required to activate this API");
        var credential = new Credential(
                scope.accountId(),
                provider,
                provider.getSlug(),
                Credential.strategyOf(provider),
                request.expiresAt(),
                request.enabled());
        // An anonymous destination reserves its path and stores nothing at it, so that changing its
        // mind later is a write rather than a new record with a new identity.
        if (request.carriesSecret()) openBao.write(credential.getSecretPath(), request.secret());
        repository.save(credential);
        audit.recordAdmin(
                AuditAction.CREDENTIAL_CREATED,
                provider.getId(),
                credential.getId().toString());
        return CredentialResponse.of(credential);
    }

    @Transactional
    public CredentialResponse update(UUID id, CredentialRequest request) {
        var credential = require(id);
        if (!credential.getProvider().getId().equals(request.providerId()))
            throw new IllegalArgumentException("Credential provider cannot be changed; create a separate credential");
        var provider = credential.getProvider();
        validateSecret(provider, request);
        if (credential.requiresReprovision() && !request.carriesSecret())
            throw new IllegalArgumentException("New credentials are required because the API authentication changed");
        credential.describe(
                provider.getSlug(), Credential.strategyOf(provider), request.expiresAt(), request.enabled());
        if (request.carriesSecret()) {
            openBao.write(credential.getSecretPath(), request.secret());
            credential.markProvisioned();
        }
        // Stored responses are addressed by credential. A rotated secret can mean a different
        // identity upstream, so nothing fetched with the old one may be served after it.
        traffic.forgetCredential(id);
        audit.recordAdmin(
                AuditAction.CREDENTIAL_UPDATED,
                credential.getProvider().getId(),
                credential.getId().toString());
        return CredentialResponse.of(credential);
    }

    @Transactional
    public void delete(UUID id) {
        var credential = require(id);
        var subscriptions = grants.findAllByCredentialId(id);
        subscriptions.forEach(grant -> traffic.forgetGrant(grant.getId()));
        // Through the session, not in one bulk statement: a batch delete leaves these grants managed
        // and still pointing at the credential removed just below, and the flush at commit refuses
        // that as an HTTP 500 rather than as anything the console can act on.
        grants.deleteAll(subscriptions);
        String secretPath = credential.getSecretPath();
        UUID providerId = credential.getProvider().getId();
        boolean stored = !credential.getAuthType().anonymous();
        repository.delete(credential);
        traffic.forgetCredential(id);
        if (stored) secretDeletions.enqueue(secretPath);
        audit.recordAdmin(AuditAction.CREDENTIAL_DELETED, providerId, id.toString());
    }

    /**
     * Scoped through the provider, which a secret can never leave. Somebody else's secret answers
     * "not found" rather than "not allowed", so an identifier cannot be confirmed by probing.
     */
    private Credential require(UUID id) {
        return repository
                .findOwnedBy(id, scope.ownerFilter())
                .orElseThrow(() -> new NotFoundException("Credential not found"));
    }

    private Provider provider(UUID id) {
        return providers.findById(id).orElseThrow(() -> new NotFoundException("Provider not found"));
    }

    /**
     * The strategy, with its token endpoint held to the same standard as any other address Janus will
     * call: HTTPS, no credentials or query in the URL, and not resolving to a private address. It is
     * a destination the gateway dials with a secret in hand, so it gets the destination rules.
     */
    private void validateSecret(Provider provider, CredentialRequest request) {
        if (!request.carriesSecret()) return;
        switch (provider.getAuthType()) {
            case NONE -> throw new IllegalArgumentException("This API does not accept credentials");
            case BASIC, OAUTH2_CLIENT_CREDENTIALS -> {
                if (request.secret().indexOf(':') < 0)
                    throw new IllegalArgumentException("Credentials must be supplied as identifier:secret");
            }
            default -> {
                // The stored value travels as supplied.
            }
        }
    }
}
