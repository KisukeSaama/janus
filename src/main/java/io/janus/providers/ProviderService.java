package io.janus.providers;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.janus.accounts.AccessScope;
import io.janus.accounts.AccountRepository;
import io.janus.audit.AuditAction;
import io.janus.audit.AuditService;
import io.janus.credentials.CredentialRepository;
import io.janus.gateway.TrafficPolicyRegistry;
import io.janus.shared.NotFoundException;

/**
 * The destinations Janus is allowed to forward to.
 *
 * <p>Any write here drops what the gateway is holding for that destination. The address, the reuse
 * policy, and the allowance can all have changed, and nothing kept under the old policy may answer
 * under the new one.
 */
@Service
public class ProviderService {
    private final ProviderRepository repository;
    private final CredentialRepository credentials;
    private final DestinationValidator destinations;
    private final TrafficPolicyRegistry traffic;
    private final AccountRepository accounts;
    private final AccessScope scope;
    private final AuditService audit;

    public ProviderService(
            ProviderRepository repository,
            CredentialRepository credentials,
            DestinationValidator destinations,
            TrafficPolicyRegistry traffic,
            AccountRepository accounts,
            AccessScope scope,
            AuditService audit) {
        this.repository = repository;
        this.credentials = credentials;
        this.destinations = destinations;
        this.traffic = traffic;
        this.accounts = accounts;
        this.scope = scope;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<ProviderResponse> list() {
        return repository.findAllOwnedBy(scope.ownerFilter()).stream()
                .map(ProviderResponse::of)
                .toList();
    }

    @Transactional
    public ProviderResponse create(ProviderRequest request) {
        UUID owner = scope.ownerFilter();
        // Within one person's namespace. Somebody else's `spotify` is not a conflict with this one.
        if (repository.existsBySlugAndOwnerId(request.slug(), owner))
            throw new IllegalArgumentException("You already have an API with that slug");
        var provider = new Provider(
                accounts.getReferenceById(owner),
                request.name(),
                request.slug(),
                destination(request),
                request.enabled(),
                request.trafficPolicy());
        repository.save(provider);
        audit.recordAdmin(AuditAction.PROVIDER_CREATED, provider.getId(), provider.getSlug());
        return ProviderResponse.of(provider);
    }

    @Transactional
    public ProviderResponse update(UUID id, ProviderRequest request) {
        var provider = require(id);
        if (!provider.getSlug().equals(request.slug())
                && repository.existsBySlugAndOwnerId(request.slug(), scope.ownerFilter()))
            throw new IllegalArgumentException("You already have an API with that slug");
        provider.describe(request.name(), request.slug(), destination(request), request.enabled());
        provider.applyTrafficPolicy(request.trafficPolicy());
        traffic.forgetProvider(id);
        audit.recordAdmin(AuditAction.PROVIDER_UPDATED, provider.getId(), provider.getSlug());
        return ProviderResponse.of(provider);
    }

    @Transactional
    public void delete(UUID id) {
        var provider = require(id);
        if (credentials.existsByProviderId(id))
            throw new IllegalStateException("Provider still has credentials; delete them first");
        repository.delete(provider);
        traffic.forgetProvider(id);
        audit.recordAdmin(AuditAction.PROVIDER_DELETED, id, provider.getSlug());
    }

    /** Drops every response Janus is holding for this destination, without changing its policy. */
    @Transactional
    public int purgeCache(UUID id) {
        var provider = require(id);
        int dropped = traffic.forgetProvider(id);
        audit.recordAdmin(AuditAction.PROVIDER_CACHE_PURGED, id, provider.getSlug() + " (" + dropped + " entries)");
        return dropped;
    }

    /** Validated and normalised: the stored form is what the gateway will build every target URI on. */
    private String destination(ProviderRequest request) {
        String url = destinations.validate(request.baseUrl()).toString();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * Scoped, always. A record belonging to somebody else answers "not found" rather than "not
     * allowed": a 403 would confirm that the identifier exists, which is an enumeration oracle.
     */
    private Provider require(UUID id) {
        return repository
                .findOwnedBy(id, scope.ownerFilter())
                .orElseThrow(() -> new NotFoundException("Provider not found"));
    }
}
