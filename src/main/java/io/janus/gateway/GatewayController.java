package io.janus.gateway;

import java.util.*;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.ObjectMapper;

import io.janus.audit.AuditOutcome;
import io.janus.audit.AuditService;
import io.janus.credentials.TokenExchangeException;
import io.janus.grants.GrantRepository;
import io.janus.providers.*;
import io.janus.security.GatewayPrincipal;
import io.janus.shared.ApiProblem;
import io.janus.shared.CorrelationIdFilter;
import io.janus.shared.ErrorCode;

/**
 * The controlled proxy. Every request passes the same sequence: identify the provider, confirm an
 * active grant, and only then read the credential. Reading the secret last means an unauthorised
 * call never causes a secret to leave OpenBao.
 *
 * <p>The path itself is not a decision. A grant admits a caller to a destination, and from there the
 * caller reaches whatever that destination exposes: the API's own authorisation is what says which
 * of its paths this credential may touch, and restating a subset of it here would only be a second,
 * staler copy of that answer.
 *
 * <p>This class decides only who may call what. Once that is settled, {@link GatewayTrafficService}
 * owns the outbound half — reuse, allowances, retries — so a caller inherits all of it without
 * asking, and none of it can run before authorisation has.
 *
 * <p>Every way this ends is answered with the same problem document, carrying an {@link ErrorCode}
 * the caller can branch on and the correlation identifier the audit record was written under. The
 * codes are the point: three distinct 429s and two distinct 403s live here, and telling them apart
 * decides whether a client should slow down, wait, or stop and go and fix something.
 */
@RestController
@RequestMapping("/gateway")
public class GatewayController {
    private static final Logger log = LoggerFactory.getLogger(GatewayController.class);

    private static final Set<HttpMethod> SUPPORTED_METHODS = Set.of(
            HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.HEAD);

    private final ProviderRepository providers;
    private final GrantRepository grants;
    private final AuthorizationCache authorizations;
    private final DestinationValidator destinations;
    private final GatewayTrafficService traffic;
    private final AuditService audit;
    private final GatewayMetrics metrics;
    private final ObjectMapper mapper;

    public GatewayController(
            ProviderRepository providers,
            GrantRepository grants,
            AuthorizationCache authorizations,
            DestinationValidator destinations,
            GatewayTrafficService traffic,
            AuditService audit,
            GatewayMetrics metrics,
            ObjectMapper mapper) {
        this.providers = providers;
        this.grants = grants;
        this.authorizations = authorizations;
        this.destinations = destinations;
        this.traffic = traffic;
        this.audit = audit;
        this.metrics = metrics;
        this.mapper = mapper;
    }

    @RequestMapping("/{slug}/**")
    public ResponseEntity<byte[]> proxy(
            @PathVariable String slug,
            @AuthenticationPrincipal GatewayPrincipal principal,
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body) {
        var call = new Call(request, principal);
        try {
            var route = GatewayPath.parse(request.getRequestURI(), slug, request.getQueryString());
            call.routed(route.decodedPath());

            var method = HttpMethod.valueOf(request.getMethod());
            if (!SUPPORTED_METHODS.contains(method))
                throw new Denied(
                        HttpStatus.METHOD_NOT_ALLOWED,
                        ErrorCode.METHOD_NOT_SUPPORTED,
                        "HTTP method is not supported by the gateway");

            // Both reads go through the short-lived registry cache. What it holds is what an
            // administrative change invalidates, so an authorisation decision is never older than
            // the change that should have altered it — see AuthorizationCache.
            var provider = authorizations
                    .provider(slug, () -> providers.findBySlugAndEnabledTrue(slug))
                    .orElseThrow(() -> new Denied(
                            HttpStatus.NOT_FOUND, ErrorCode.PROVIDER_UNAVAILABLE, "Provider is not available"));
            call.reached(provider);

            var grant = authorizations
                    .grant(
                            principal.applicationId(),
                            provider.getId(),
                            () -> grants.findActive(principal.applicationId(), provider.getId()))
                    .orElseThrow(() -> new Denied(
                            HttpStatus.FORBIDDEN, ErrorCode.GRANT_MISSING, "No active grant for this provider"));
            if (!grant.getCredential().isEnabled())
                throw new Denied(HttpStatus.FORBIDDEN, ErrorCode.CREDENTIAL_DISABLED, "Credential is disabled");

            // Deliberately after the grant, and it used to be before it. A registered address can stop
            // satisfying the rules it was accepted under — the deployment stops offering local
            // destinations, or a base URL is edited — and what the validator says about that is
            // specific enough to act on, which is exactly why it must not be readable by a caller who
            // has no grant for this provider. Behind the grant, the caller is entitled to the reason.
            try {
                destinations.validateShape(provider.getBaseUrl(), provider.isAllowPrivateDestination());
            } catch (IllegalArgumentException ex) {
                throw new Denied(HttpStatus.BAD_GATEWAY, ErrorCode.PROVIDER_MISCONFIGURED, ex.getMessage());
            }

            var exchange = new GatewayExchange(
                    provider,
                    grant,
                    principal.applicationId(),
                    method,
                    route,
                    forwardedHeaders(request),
                    body,
                    call.correlationId);
            var outcome = traffic.forward(exchange);

            var headers = outcome.headers();
            headers.set(CorrelationIdFilter.RESPONSE_HEADER, call.correlationId);
            call.finish(AuditOutcome.SUCCESS, outcome.status().value(), outcome.auditDetail(), outcome.cacheStatus());
            return new ResponseEntity<>(outcome.body(), headers, outcome.status());

        } catch (Throttled throttled) {
            // Refused to protect an allowance, not because the caller lacked permission. The caller
            // is told exactly how long to wait, which is all it needs to behave correctly.
            call.finish(AuditOutcome.THROTTLED, HttpStatus.TOO_MANY_REQUESTS.value(), throttled.getMessage(), null);
            var headers = throttled.headers;
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(throttled.retryAfterSeconds));
            return problem(HttpStatus.TOO_MANY_REQUESTS, throttled.code, throttled.getMessage(), call, headers);
        } catch (Denied denied) {
            // A misconfigured provider is refused through the same door, and it is not the caller's
            // mistake: the journal should not read as though an application tried something it may
            // not do. The status is what separates the two.
            var outcome = denied.status.is5xxServerError() ? AuditOutcome.ERROR : AuditOutcome.DENIED;
            call.finish(outcome, denied.status.value(), denied.getMessage(), null);
            return problem(denied.status, denied.code, denied.getMessage(), call, new HttpHeaders());
        } catch (GatewayHttpClientConfig.BlockedDestinationException ex) {
            String detail = "Destination address is not permitted";
            call.finish(AuditOutcome.DENIED, HttpStatus.BAD_GATEWAY.value(), detail, null);
            return problem(HttpStatus.BAD_GATEWAY, ErrorCode.DESTINATION_BLOCKED, detail, call, new HttpHeaders());
        } catch (TokenExchangeException ex) {
            // Janus could not obtain the token this credential needs, so the request was never sent —
            // it fails closed rather than reaching the API without credentials. Named separately from
            // the generic failure below because the thing to go and look at is different: the client
            // credentials, or the token endpoint, not the API being called. The message carries a
            // status at most, never the token endpoint's response body.
            call.finish(AuditOutcome.ERROR, HttpStatus.BAD_GATEWAY.value(), ex.getMessage(), null);
            return problem(
                    HttpStatus.BAD_GATEWAY,
                    ErrorCode.TOKEN_EXCHANGE_FAILED,
                    "Could not obtain an access token for this credential",
                    call,
                    new HttpHeaders());
        } catch (WebClientResponseException ex) {
            // This exception's message embeds the upstream response body, and an upstream that
            // rejects a credential often quotes it back. Only the status is recorded.
            log.warn(
                    "Gateway upstream returned {} [correlationId={}]",
                    ex.getStatusCode().value(),
                    call.correlationId);
            call.finish(
                    AuditOutcome.ERROR,
                    HttpStatus.BAD_GATEWAY.value(),
                    "Upstream returned " + ex.getStatusCode().value(),
                    null);
            return problem(
                    HttpStatus.BAD_GATEWAY,
                    ErrorCode.UPSTREAM_FAILED,
                    "The provider's response could not be completed",
                    call,
                    new HttpHeaders());
        } catch (Exception ex) {
            // Everything that never produced a response. Which of those it was decides the answer:
            // see UpstreamFailure. The exception itself is logged and never returned, because its
            // message can quote internal detail.
            var failure = UpstreamFailure.of(ex);
            if (failure.code() == ErrorCode.INTERNAL_ERROR)
                log.error("Gateway request failed inside Janus [correlationId={}]", call.correlationId, ex);
            else log.warn("Gateway could not reach the provider [correlationId={}]", call.correlationId, ex);
            call.finish(AuditOutcome.ERROR, failure.status().value(), failure.detail(), null);
            return problem(failure.status(), failure.code(), failure.detail(), call, new HttpHeaders());
        }
    }

    /**
     * What is known about one call so far, so that every way it can end reports the same things
     * without six branches each rebuilding them.
     */
    private final class Call {
        private final long startedAt = System.nanoTime();
        private final String correlationId = CorrelationIdFilter.current();
        private final HttpServletRequest request;
        private final GatewayPrincipal principal;
        private UUID providerId;
        private String providerSlug;
        private String decodedPath;

        private Call(HttpServletRequest request, GatewayPrincipal principal) {
            this.request = request;
            this.principal = principal;
        }

        private void routed(String decodedPath) {
            this.decodedPath = decodedPath;
        }

        private void reached(Provider provider) {
            this.providerId = provider.getId();
            this.providerSlug = provider.getSlug();
        }

        private void finish(AuditOutcome outcome, int status, String detail, CacheStatus cacheStatus) {
            audit.recordGateway(new AuditService.GatewayEvent(
                    principal.applicationId(),
                    principal.ownerId(),
                    outcome,
                    providerId,
                    request.getMethod(),
                    decodedPath,
                    status,
                    detail,
                    correlationId));
            // The slug is only tagged once a provider was actually resolved. Tagging the requested
            // one would let any caller mint an unbounded number of time series.
            metrics.record(providerSlug, outcome, cacheStatus, status, System.nanoTime() - startedAt);
        }
    }

    private HttpHeaders forwardedHeaders(HttpServletRequest request) {
        var outbound = new HttpHeaders();
        Collections.list(request.getHeaderNames()).forEach(name -> {
            if (HeaderPolicy.isRequestHeaderForwarded(name))
                outbound.put(name, Collections.list(request.getHeaders(name)));
        });
        return outbound;
    }

    /**
     * The one way this endpoint refuses. {@code X-Janus-Error} is what makes a refusal Janus made
     * distinguishable from one the upstream made: an upstream's own 403 is relayed byte for byte and
     * carries no such header.
     */
    private ResponseEntity<byte[]> problem(
            HttpStatus status, ErrorCode code, String detail, Call call, HttpHeaders headers) {
        byte[] payload = mapper.writeValueAsBytes(ApiProblem.body(status, code, detail));
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        headers.set(ApiProblem.HEADER, code.wire());
        headers.set(CorrelationIdFilter.RESPONSE_HEADER, call.correlationId);
        return ResponseEntity.status(status).headers(headers).body(payload);
    }

    /** Signals a decision the gateway made itself, as opposed to an upstream failure. */
    static class Denied extends RuntimeException {
        final HttpStatus status;
        final ErrorCode code;

        Denied(HttpStatus status, ErrorCode code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }
}
