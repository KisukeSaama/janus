package io.janus.gateway;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import io.janus.credentials.Identity;
import io.janus.grants.Grant;
import io.janus.grants.GrantRepository;
import io.janus.grants.GrantScope;
import io.janus.providers.DestinationValidator;
import io.janus.providers.Provider;
import io.janus.providers.ProviderRepository;
import io.janus.security.GatewayPrincipal;
import io.janus.shared.ErrorCode;

/**
 * The questions every way into the gateway asks before anything is forwarded: which destination,
 * whether this application holds a grant on it, what that grant admits, and whom the call may speak
 * as. Kept in one place because there are two doors, an HTTP request and a WebSocket handshake, and
 * a decision made two ways is a decision that will one day be made two different ways.
 *
 * <p>Each step is its own method so a caller can take them in the order its own protocol needs, and
 * each refuses with the code a caller can act on. None of them reads a secret.
 */
@Component
public class GatewayAdmission {
    private final ProviderRepository providers;
    private final GrantRepository grants;
    private final AuthorizationCache authorizations;
    private final DestinationValidator destinations;

    public GatewayAdmission(
            ProviderRepository providers,
            GrantRepository grants,
            AuthorizationCache authorizations,
            DestinationValidator destinations) {
        this.providers = providers;
        this.grants = grants;
        this.authorizations = authorizations;
        this.destinations = destinations;
    }

    /**
     * Both reads go through the short-lived registry cache. What it holds is what an administrative
     * change invalidates, so an authorisation decision is never older than the change that should have
     * altered it; see {@link AuthorizationCache}.
     */
    Provider provider(String slug) {
        return authorizations
                .provider(slug, () -> providers.findBySlugAndEnabledTrue(slug))
                .orElseThrow(() -> new GatewayController.Denied(
                        HttpStatus.NOT_FOUND, ErrorCode.PROVIDER_UNAVAILABLE, "Provider is not available"));
    }

    Grant grant(GatewayPrincipal principal, Provider provider) {
        var grant = authorizations
                .grant(
                        principal.applicationId(),
                        provider.getId(),
                        () -> grants.findActive(principal.applicationId(), provider.getId()))
                .orElseThrow(() -> new GatewayController.Denied(
                        HttpStatus.FORBIDDEN, ErrorCode.GRANT_MISSING, "No active grant for this provider"));
        if (!grant.getCredential().isEnabled())
            throw new GatewayController.Denied(
                    HttpStatus.FORBIDDEN, ErrorCode.CREDENTIAL_DISABLED, "Credential is disabled");
        return grant;
    }

    /**
     * What of the destination this grant admits, which is ordinarily all of it. Refused before the
     * credential is read and before anything is forwarded, so a call outside the ceiling costs the
     * upstream nothing and leaves the secret where it is.
     */
    void checkMethod(GrantScope scope, HttpMethod method) {
        if (!scope.admitsMethod(method.name()))
            throw new GatewayController.Denied(
                    HttpStatus.FORBIDDEN,
                    ErrorCode.METHOD_NOT_GRANTED,
                    "This grant does not admit " + method.name() + " on this API");
    }

    void checkPath(GrantScope scope, GatewayPath route) {
        if (!scope.admitsPath(route.decodedPath()))
            throw new GatewayController.Denied(
                    HttpStatus.FORBIDDEN,
                    ErrorCode.PATH_NOT_GRANTED,
                    "This grant admits only " + scope.pathPrefix() + " and what is under it");
    }

    /**
     * Deliberately after the grant, and it used to be before it. A registered address can stop
     * satisfying the rules it was accepted under (the deployment stops offering local destinations,
     * or a base URL is edited), and what the validator says about that is specific enough to act on,
     * which is exactly why it must not be readable by a caller who has no grant for this provider.
     * Behind the grant, the caller is entitled to the reason.
     */
    void checkDestination(Provider provider) {
        try {
            destinations.validateShape(provider.getBaseUrl(), provider.isAllowPrivateDestination());
        } catch (IllegalArgumentException ex) {
            throw new GatewayController.Denied(
                    HttpStatus.BAD_GATEWAY, ErrorCode.PROVIDER_MISCONFIGURED, ex.getMessage());
        }
    }

    /**
     * Whom the caller asked to speak as, read from the raw request and never from the forwarded
     * headers: the X-Janus- namespace is stripped on the way out, which is exactly the property wanted
     * here. Whom this application may speak as is the grant's decision, and a header does not change
     * it; refused here, beside the path and the method, because this is the ceiling and nothing behind
     * it should have to check the ceiling again.
     */
    Identity pinned(GrantScope scope, String header) {
        Identity pinned;
        try {
            pinned = Identity.parse(header);
        } catch (IllegalArgumentException ex) {
            throw new GatewayController.Denied(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, ex.getMessage());
        }
        if (pinned == Identity.ACCOUNT && !scope.admitsAccountIdentity())
            throw new GatewayController.Denied(
                    HttpStatus.FORBIDDEN,
                    ErrorCode.IDENTITY_NOT_GRANTED,
                    "This grant does not admit speaking for the connected account");
        return pinned;
    }
}
