package io.janus.providers;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.janus.accounts.AccessScope;
import io.janus.accounts.AccountRepository;
import io.janus.accounts.AccountRole;
import io.janus.audit.AuditAction;
import io.janus.audit.AuditService;
import io.janus.credentials.Credential;
import io.janus.credentials.CredentialRepository;
import io.janus.gateway.TrafficPolicyRegistry;
import io.janus.grants.GrantRepository;
import io.janus.openbao.SecretDeletionQueue;
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
    private final GrantRepository grants;
    private final SecretDeletionQueue secretDeletions;
    private final DestinationValidator destinations;
    private final UpstreamPing upstream;
    private final TrafficPolicyRegistry traffic;
    private final AccountRepository accounts;
    private final AccessScope scope;
    private final AuditService audit;

    public ProviderService(
            ProviderRepository repository,
            CredentialRepository credentials,
            GrantRepository grants,
            SecretDeletionQueue secretDeletions,
            DestinationValidator destinations,
            UpstreamPing upstream,
            TrafficPolicyRegistry traffic,
            AccountRepository accounts,
            AccessScope scope,
            AuditService audit) {
        this.repository = repository;
        this.credentials = credentials;
        this.grants = grants;
        this.secretDeletions = secretDeletions;
        this.destinations = destinations;
        this.upstream = upstream;
        this.traffic = traffic;
        this.accounts = accounts;
        this.scope = scope;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<ProviderResponse> list() {
        return repository.findAll(Sort.by("name")).stream()
                .map(ProviderResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProviderPage catalog(String query, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        var result = repository.search(
                query == null ? "" : query.trim(), PageRequest.of(Math.max(page, 0), safeSize, Sort.by("name")));
        var ids = result.getContent().stream().map(Provider::getId).toList();
        var active =
                ids.isEmpty() ? java.util.Set.<UUID>of() : credentials.findActivatedProviderIds(scope.accountId(), ids);
        var content = result.getContent().stream()
                .map(provider -> ProviderResponse.of(provider, active.contains(provider.getId())))
                .toList();
        return new ProviderPage(
                content, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional
    public ProviderResponse create(ProviderRequest request) {
        requireAdministrator();
        if (repository.existsBySlug(request.slug()))
            throw new IllegalArgumentException("An API with that slug already exists");
        var provider = new Provider(
                request.name(),
                request.slug(),
                destination(request),
                request.enabled(),
                request.allowPrivateDestination(),
                request.trafficPolicy(),
                auth(request),
                connection(request));
        provider.applyNormalization(request.normalization());
        repository.save(provider);
        audit.recordAdmin(AuditAction.PROVIDER_CREATED, provider.getId(), provider.getSlug());
        return ProviderResponse.of(provider);
    }

    @Transactional
    public ProviderResponse update(UUID id, ProviderRequest request) {
        requireAdministrator();
        var provider = require(id);
        if (!provider.getSlug().equals(request.slug()) && repository.existsBySlug(request.slug()))
            throw new IllegalArgumentException("An API with that slug already exists");
        var previousAuth = provider.getAuthType();
        var previousConnection = provider.connection();
        provider.describe(
                request.name(),
                request.slug(),
                destination(request),
                request.enabled(),
                request.allowPrivateDestination());
        provider.applyTrafficPolicy(request.trafficPolicy());
        provider.applyNormalization(request.normalization());
        provider.applyAuth(auth(request));
        provider.applyConnection(connection(request));
        var personalCredentials = credentials.findAllByProviderId(id);
        // An anonymous destination that still offers a connection keeps its stored value: with nothing
        // of the application's in the way, that is the very path the connection's OAuth client uses.
        if (!previousAuth.anonymous() && provider.getAuthType().anonymous() && !provider.offersConnection())
            secretDeletions.enqueueAll(
                    personalCredentials.stream().map(Credential::getSecretPath).toList());
        // A connection withdrawn leaves its own client secret behind when it had one of its own.
        if (previousConnection.offered() && !provider.offersConnection())
            secretDeletions.enqueueAll(personalCredentials.stream()
                    .filter(credential -> !credential.connectionSharesApplicationSecret())
                    .map(Credential::connectionSecretPath)
                    .toList());
        personalCredentials.forEach(credential -> credential.adoptProviderStrategy(previousAuth, previousConnection));
        traffic.forgetProvider(id);
        audit.recordAdmin(AuditAction.PROVIDER_UPDATED, provider.getId(), provider.getSlug());
        return ProviderResponse.of(provider);
    }

    @Transactional
    public void delete(UUID id) {
        requireAdministrator();
        var provider = require(id);

        // An API is one operator-facing record even though the gateway stores it as a provider,
        // credential and any number of grants. Remove that aggregate in dependency order so a
        // failed delete cannot leave the slug occupied by an invisible provider.
        //
        // Every removal goes through the session rather than a bulk statement, and the database
        // cascade behind it is only a backstop. Reading a grant here makes it managed, and a managed
        // grant still points at the provider and the credential it was read with; Hibernate refuses
        // to flush a live row referencing a removed one, which is the opaque HTTP 500 an operator
        // used to get for any API a service was connected to.
        var connectedGrants = grants.findAllByProviderId(id);
        for (var grant : connectedGrants) traffic.forgetGrant(grant.getId());
        grants.deleteAll(connectedGrants);

        var heldCredentials = credentials.findAllByProviderId(id);
        var storedPaths = heldCredentials.stream()
                .filter(credential -> !credential.getAuthType().anonymous())
                .map(credential -> credential.getSecretPath())
                .toList();
        for (var credential : heldCredentials) traffic.forgetCredential(credential.getId());
        credentials.deleteAll(heldCredentials);

        repository.delete(provider);
        traffic.forgetProvider(id);
        secretDeletions.enqueueAll(storedPaths);
        audit.recordAdmin(AuditAction.PROVIDER_DELETED, id, provider.getSlug());
    }

    /** Drops every response Janus is holding for this destination, without changing its policy. */
    @Transactional
    public int purgeCache(UUID id) {
        requireAdministrator();
        var provider = require(id);
        int dropped = traffic.forgetProvider(id);
        audit.recordAdmin(AuditAction.PROVIDER_CACHE_PURGED, id, provider.getSlug() + " (" + dropped + " entries)");
        return dropped;
    }

    /**
     * Whether this destination is answering, right now.
     *
     * <p>Deliberately outside a transaction: the call leaves the process and waits on somebody else's
     * network, and a database connection held for that whole wait is a connection the gateway is not
     * proxying with. The read it needs is one row, and the pool is small on purpose.
     *
     * <p>Restricted to administrators like every other action on a catalogue entry. Nothing is
     * disclosed by the answer, but the address is theirs to maintain, and a probe anybody could ask
     * for is outbound traffic anybody could raise from the deployment's own address.
     */
    public ProviderPing ping(UUID id) {
        requireAdministrator();
        var provider = require(id);
        return upstream.reach(provider.getBaseUrl(), provider.isAllowPrivateDestination());
    }

    /** Configuration rather than data, so no account check and nothing to audit. */
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(destinations.isOfferingPrivateDestinations());
    }

    /** Validated and normalised: the stored form is what the gateway will build every target URI on. */
    private String destination(ProviderRequest request) {
        String url = destinations
                .validate(request.baseUrl(), request.allowPrivateDestination())
                .toString();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private Provider.Auth auth(ProviderRequest request) {
        var auth = request.auth();
        switch (auth.type()) {
            case API_KEY_HEADER -> {
                if (auth.headerName() == null || !auth.headerName().matches("[A-Za-z0-9-]{1,100}"))
                    throw new IllegalArgumentException("A valid header name is required for a header API key");
            }
            case API_KEY_QUERY -> {
                if (auth.queryParameter() == null || !auth.queryParameter().matches("[A-Za-z0-9._~-]{1,100}"))
                    throw new IllegalArgumentException("A valid query parameter name is required");
            }
            case OAUTH2_CLIENT_CREDENTIALS -> {
                if (auth.tokenUrl() == null || auth.tokenUrl().isBlank())
                    throw new IllegalArgumentException("A token endpoint is required");
                // Checked like any other header name, because that is what it becomes: a field
                // written onto every outbound request, where a stray character is header injection.
                if (hasText(auth.clientIdHeader()) && !auth.clientIdHeader().matches("[A-Za-z0-9-]{1,100}"))
                    throw new IllegalArgumentException("A valid header name is required for the client id");
                auth = new Provider.Auth(
                        auth.type(),
                        null,
                        null,
                        destinations.validate(auth.tokenUrl()).toString(),
                        auth.tokenScopes(),
                        auth.tokenClientAuth(),
                        null,
                        auth.clientIdHeader());
            }
            case HMAC_SIGNATURE -> {
                if (auth.headerName() != null && !auth.headerName().matches("[A-Za-z0-9-]{1,100}"))
                    throw new IllegalArgumentException("A valid header name is required for the key");
                if (auth.signature() == null || auth.signature().template() == null)
                    throw new IllegalArgumentException("A signing recipe is required");
                auth.signature().validate();
            }
            default -> {
                // No extra configuration belongs to this strategy.
            }
        }
        return auth;
    }

    /**
     * The account connection, or one offering nothing.
     *
     * <p>Half a connection is refused rather than quietly dropped. An authorisation page with nowhere
     * to exchange the code fails at the moment somebody has already agreed at the provider's site,
     * which is the worst possible time to discover a form was incomplete.
     */
    private Provider.Connection connection(ProviderRequest request) {
        var connection = request.connection();
        boolean authorization = hasText(connection.authorizationUrl());
        boolean token = hasText(connection.tokenUrl());
        if (!authorization && !token) return Provider.Connection.none();
        if (!authorization)
            throw new IllegalArgumentException("An authorisation page is required, where the account holder agrees "
                    + "— such as https://accounts.spotify.com/authorize");
        if (!token) throw new IllegalArgumentException("A token endpoint is required for an account connection");

        // Both are destinations Janus will send somebody or something to, so both are checked by the
        // same rules that admit any other URL: no private address, no redirect chain.
        return new Provider.Connection(
                destinations.validate(connection.authorizationUrl()).toString(),
                destinations.validate(connection.tokenUrl()).toString(),
                connection.scopes(),
                connection.clientAuth());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void requireAdministrator() {
        if (scope.role() == AccountRole.USER)
            throw new org.springframework.security.access.AccessDeniedException("Only administrators manage APIs");
    }

    /**
     * Scoped, always. A record belonging to somebody else answers "not found" rather than "not
     * allowed": a 403 would confirm that the identifier exists, which is an enumeration oracle.
     */
    private Provider require(UUID id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Provider not found"));
    }
}
