package io.janus.applications;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.janus.accounts.AccessScope;
import io.janus.accounts.AccountRepository;
import io.janus.audit.AuditAction;
import io.janus.audit.AuditService;
import io.janus.oauth.AccessTokenStore;
import io.janus.oauth.RefreshTokenRepository;
import io.janus.security.ApiKeyCache;
import io.janus.security.ApiKeyGenerator;
import io.janus.shared.NotFoundException;

/**
 * Machine identities and the keys they hold.
 *
 * <p>Every change that could affect who may call the gateway drops the verified-key cache entry for
 * that identity, so a rotation or a disabling is enforced on the next request rather than whenever
 * the entry happens to expire.
 */
@Service
public class ApplicationService {
    private final ApplicationRepository repository;
    private final ApiKeyGenerator keys;
    private final ApiKeyCache keyCache;
    private final AccessTokenStore accessTokens;
    private final RefreshTokenRepository refreshTokens;
    private final AccountRepository accounts;
    private final AccessScope scope;
    private final AuditService audit;

    public ApplicationService(
            ApplicationRepository repository,
            ApiKeyGenerator keys,
            ApiKeyCache keyCache,
            AccessTokenStore accessTokens,
            RefreshTokenRepository refreshTokens,
            AccountRepository accounts,
            AccessScope scope,
            AuditService audit) {
        this.repository = repository;
        this.keys = keys;
        this.keyCache = keyCache;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.accounts = accounts;
        this.scope = scope;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<ApplicationResponse> list() {
        return repository.findAllOwnedBy(scope.ownerFilter()).stream()
                .map(ApplicationResponse::of)
                .toList();
    }

    @Transactional
    public IssuedApplication create(ApplicationRequest request) {
        UUID owner = scope.ownerFilter();
        if (repository.existsByOwnerIdAndName(owner, request.name()))
            throw new IllegalArgumentException("You already have a service with that name");
        var key = keys.issue();
        var application = new Application(
                accounts.getReferenceById(owner), request.name(), request.description(), request.enabled(), key.hash());
        application.allowOrigins(request.allowedOrigins());
        repository.save(application);
        audit.recordAdmin(
                AuditAction.APPLICATION_CREATED, null, application.getId().toString());
        return new IssuedApplication(ApplicationResponse.of(application), key.value());
    }

    @Transactional
    public ApplicationResponse update(UUID id, ApplicationRequest request) {
        var application = require(id);
        application.describe(request.name(), request.description(), request.enabled());
        application.allowOrigins(request.allowedOrigins());
        forget(id);
        audit.recordAdmin(AuditAction.APPLICATION_UPDATED, null, id.toString());
        return ApplicationResponse.of(application);
    }

    /** Issues a new key and invalidates the previous one at once. */
    @Transactional
    public IssuedApplication rotateKey(UUID id) {
        var application = require(id);
        var key = keys.issue();
        application.rotateApiKey(key.hash());
        forget(id);
        // A rotation is how a leaked key is taken back, so it has to take back what the old key was
        // exchanged for as well: an access token outliving it would leave the leak working.
        refreshTokens.deleteByApplicationId(id);
        audit.recordAdmin(AuditAction.APPLICATION_KEY_ROTATED, null, id.toString());
        return new IssuedApplication(ApplicationResponse.of(application), key.value());
    }

    @Transactional
    public void delete(UUID id) {
        repository.delete(require(id));
        forget(id);
        audit.recordAdmin(AuditAction.APPLICATION_DELETED, null, id.toString());
    }

    /**
     * Drops everything held in memory about this identity. Both caches, always together: a verified
     * key and an issued token stand for the same statement, and a change that only reached one of
     * them would take effect for some callers and not others.
     *
     * <p>Refresh tokens survive an ordinary edit — renaming a service should not sign its clients
     * out — and are dropped only where the credential itself is taken back.
     */
    private void forget(UUID id) {
        keyCache.invalidate(id);
        accessTokens.revokeApplication(id);
    }

    /**
     * Scoped, always. A record belonging to somebody else answers "not found" rather than "not
     * allowed": a 403 would confirm that the identifier exists, which is an enumeration oracle.
     */
    private Application require(UUID id) {
        return repository
                .findOwnedBy(id, scope.ownerFilter())
                .orElseThrow(() -> new NotFoundException("Application not found"));
    }
}
