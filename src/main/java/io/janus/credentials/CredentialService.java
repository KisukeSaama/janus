package io.janus.credentials;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.janus.accounts.AccessScope;
import io.janus.audit.AuditAction;
import io.janus.audit.AuditService;
import io.janus.gateway.TrafficPolicyRegistry;
import io.janus.grants.GrantRepository;
import io.janus.openbao.OpenBaoClient;
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
    private final TrafficPolicyRegistry traffic;
    private final DestinationValidator destinations;
    private final AccessScope scope;
    private final AuditService audit;

    public CredentialService(
            CredentialRepository repository,
            ProviderRepository providers,
            GrantRepository grants,
            OpenBaoClient openBao,
            TrafficPolicyRegistry traffic,
            DestinationValidator destinations,
            AccessScope scope,
            AuditService audit) {
        this.repository = repository;
        this.providers = providers;
        this.grants = grants;
        this.openBao = openBao;
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
        if (!request.carriesSecret() && !request.authType().anonymous())
            throw new IllegalArgumentException("Secret is required when creating a credential");
        request.validate();
        var provider = provider(request.providerId());
        var credential =
                new Credential(provider, request.name(), strategy(request), request.expiresAt(), request.enabled());
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
        request.validate();
        // Crossing into presenting something needs the thing to present: nothing was ever stored at
        // this path, and an update that left it empty would fail on the first proxied call instead of
        // here.
        if (credential.getAuthType().anonymous() && !request.authType().anonymous() && !request.carriesSecret())
            throw new IllegalArgumentException("Secret is required to give this credential a strategy");
        boolean abandonsSecret =
                !credential.getAuthType().anonymous() && request.authType().anonymous();
        credential.describe(request.name(), strategy(request), request.expiresAt(), request.enabled());
        if (request.carriesSecret()) openBao.write(credential.getSecretPath(), request.secret());
        // Crossing the other way leaves a value nothing will ever read again. It is destroyed for the
        // same reason a deleted credential's is: a secret Janus no longer uses is a secret Janus has
        // no business keeping.
        if (abandonsSecret) destroyAfterCommit(credential.getSecretPath());
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
        if (grants.existsByCredentialId(id))
            throw new IllegalStateException("Credential is still used by an access grant");
        String secretPath = credential.getSecretPath();
        UUID providerId = credential.getProvider().getId();
        boolean stored = !credential.getAuthType().anonymous();
        repository.delete(credential);
        traffic.forgetCredential(id);
        if (stored) destroyAfterCommit(secretPath);
        audit.recordAdmin(AuditAction.CREDENTIAL_DELETED, providerId, id.toString());
    }

    /**
     * Destroys a stored value once the metadata that pointed at it is durably gone. Never before: an
     * orphaned OpenBao entry is recoverable, a credential row pointing at a destroyed secret is not.
     */
    private void destroyAfterCommit(String secretPath) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                openBao.delete(secretPath);
            }
        });
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
        return providers
                .findOwnedBy(id, scope.ownerFilter())
                .orElseThrow(() -> new NotFoundException("Provider not found"));
    }

    /**
     * The strategy, with its token endpoint held to the same standard as any other address Janus will
     * call: HTTPS, no credentials or query in the URL, and not resolving to a private address. It is
     * a destination the gateway dials with a secret in hand, so it gets the destination rules.
     */
    private Credential.Strategy strategy(CredentialRequest request) {
        var strategy = request.strategy();
        if (!strategy.authType().exchanged()) return strategy;
        return new Credential.Strategy(
                strategy.authType(),
                strategy.headerName(),
                strategy.queryParameter(),
                destinations.validate(strategy.tokenUrl()).toString(),
                strategy.tokenScopes(),
                strategy.tokenClientAuth());
    }
}
