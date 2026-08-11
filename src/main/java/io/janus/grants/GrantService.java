package io.janus.grants;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.janus.accounts.AccessScope;
import io.janus.applications.Application;
import io.janus.applications.ApplicationRepository;
import io.janus.audit.AuditAction;
import io.janus.audit.AuditService;
import io.janus.credentials.Credential;
import io.janus.credentials.CredentialRepository;
import io.janus.gateway.TrafficPolicyRegistry;
import io.janus.providers.Provider;
import io.janus.providers.ProviderRepository;
import io.janus.shared.NotFoundException;

/** Who may call what. Everything the gateway authorises comes from a grant written here. */
@Service
public class GrantService {
    private final GrantRepository repository;
    private final ApplicationRepository applications;
    private final ProviderRepository providers;
    private final CredentialRepository credentials;
    private final TrafficPolicyRegistry traffic;
    private final AccessScope scope;
    private final AuditService audit;

    public GrantService(
            GrantRepository repository,
            ApplicationRepository applications,
            ProviderRepository providers,
            CredentialRepository credentials,
            TrafficPolicyRegistry traffic,
            AccessScope scope,
            AuditService audit) {
        this.repository = repository;
        this.applications = applications;
        this.providers = providers;
        this.credentials = credentials;
        this.traffic = traffic;
        this.scope = scope;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<GrantResponse> list() {
        return repository.findAllOwnedBy(scope.ownerFilter()).stream()
                .map(GrantResponse::of)
                .toList();
    }

    @Transactional
    public GrantResponse create(GrantRequest request) {
        var grant = new Grant(
                application(request.applicationId()),
                provider(request.providerId()),
                credential(request.credentialId()));
        grant.setEnabled(request.enabled());
        grant.applyQuota(request.quota());
        repository.save(grant);
        audit.recordAdmin(
                AuditAction.GRANT_CREATED,
                grant.getProvider().getId(),
                grant.getId().toString());
        return GrantResponse.of(grant);
    }

    @Transactional
    public GrantResponse update(UUID id, GrantRequest request) {
        var grant = require(id);
        grant.bind(
                application(request.applicationId()),
                provider(request.providerId()),
                credential(request.credentialId()));
        grant.setEnabled(request.enabled());
        grant.applyQuota(request.quota());
        // A new allowance must not inherit the tokens the old one had left.
        traffic.forgetGrant(id);
        audit.recordAdmin(
                AuditAction.GRANT_UPDATED,
                grant.getProvider().getId(),
                grant.getId().toString());
        return GrantResponse.of(grant);
    }

    @Transactional
    public void delete(UUID id) {
        var grant = require(id);
        UUID providerId = grant.getProvider().getId();
        repository.delete(grant);
        traffic.forgetGrant(id);
        audit.recordAdmin(AuditAction.GRANT_DELETED, providerId, id.toString());
    }

    /**
     * Every lookup below is scoped to the caller. Naming somebody else's record therefore reads as
     * "not found", which is what it is from where the caller stands — and {@code Grant.bind} refuses
     * the combination anyway, so the rule holds even if a finder here were ever widened.
     */
    private Grant require(UUID id) {
        return repository
                .findOwnedBy(id, scope.ownerFilter())
                .orElseThrow(() -> new NotFoundException("Grant not found"));
    }

    private Application application(UUID id) {
        return applications
                .findOwnedBy(id, scope.ownerFilter())
                .orElseThrow(() -> new NotFoundException("Application not found"));
    }

    private Provider provider(UUID id) {
        return providers.findById(id).orElseThrow(() -> new NotFoundException("Provider not found"));
    }

    private Credential credential(UUID id) {
        return credentials
                .findOwnedBy(id, scope.ownerFilter())
                .orElseThrow(() -> new NotFoundException("Credential not found"));
    }
}
