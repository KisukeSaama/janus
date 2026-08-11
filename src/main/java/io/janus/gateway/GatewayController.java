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
import io.janus.shared.CorrelationIdFilter;

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
 */
@RestController
@RequestMapping({"/{username}/gateway", "/gateway"})
public class GatewayController {
    private static final Logger log = LoggerFactory.getLogger(GatewayController.class);

    private static final Set<HttpMethod> SUPPORTED_METHODS = Set.of(
            HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.HEAD);

    private final ProviderRepository providers;
    private final GrantRepository grants;
    private final DestinationValidator destinations;
    private final GatewayTrafficService traffic;
    private final AuditService audit;
    private final GatewayMetrics metrics;
    private final ObjectMapper mapper;

    public GatewayController(
            ProviderRepository providers,
            GrantRepository grants,
            DestinationValidator destinations,
            GatewayTrafficService traffic,
            AuditService audit,
            GatewayMetrics metrics,
            ObjectMapper mapper) {
        this.providers = providers;
        this.grants = grants;
        this.destinations = destinations;
        this.traffic = traffic;
        this.audit = audit;
        this.metrics = metrics;
        this.mapper = mapper;
    }

    @RequestMapping("/{slug}/**")
    public ResponseEntity<byte[]> proxy(
            @PathVariable(required = false) String username,
            @PathVariable String slug,
            @AuthenticationPrincipal GatewayPrincipal principal,
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body) {
        var call = new Call(request, principal);
        try {
            if (username != null && !username.equals(principal.ownerUsername()))
                throw new Denied(HttpStatus.NOT_FOUND, "Provider is not available");
            var route = username == null
                    ? GatewayPath.parse(request.getRequestURI(), slug, request.getQueryString())
                    : GatewayPath.parse(request.getRequestURI(), username, slug, request.getQueryString());
            call.routed(route.decodedPath());

            var method = HttpMethod.valueOf(request.getMethod());
            if (!SUPPORTED_METHODS.contains(method))
                throw new Denied(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method is not supported by the gateway");

            // A slug is resolved inside the calling application's owner's namespace: two people may
            // each have registered `spotify`, and the caller decides which one it meant by which key
            // it presented. Somebody else's slug is simply not a destination here.
            var provider = providers
                    .findBySlugAndOwnerIdAndEnabledTrue(slug, principal.ownerId())
                    .orElseThrow(() -> new Denied(HttpStatus.NOT_FOUND, "Provider is not available"));
            call.reached(provider);
            destinations.validateShape(provider.getBaseUrl());

            var grant = grants.findActive(principal.applicationId(), provider.getId())
                    .orElseThrow(() -> new Denied(HttpStatus.FORBIDDEN, "No active grant for this provider"));
            if (!grant.getCredential().isEnabled()) throw new Denied(HttpStatus.FORBIDDEN, "Credential is disabled");

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
            return problem(HttpStatus.TOO_MANY_REQUESTS, throttled.getMessage(), call.correlationId, headers);
        } catch (Denied denied) {
            call.finish(AuditOutcome.DENIED, denied.status.value(), denied.getMessage(), null);
            return problem(denied.status, denied.getMessage(), call.correlationId, new HttpHeaders());
        } catch (GatewayHttpClientConfig.BlockedDestinationException ex) {
            String detail = "Destination address is not permitted";
            call.finish(AuditOutcome.DENIED, HttpStatus.BAD_GATEWAY.value(), detail, null);
            return problem(HttpStatus.BAD_GATEWAY, detail, call.correlationId, new HttpHeaders());
        } catch (TokenExchangeException ex) {
            // Janus could not obtain the token this credential needs, so the request was never sent —
            // it fails closed rather than reaching the API without credentials. Named separately from
            // the generic failure below because the thing to go and look at is different: the client
            // credentials, or the token endpoint, not the API being called. The message carries a
            // status at most, never the token endpoint's response body.
            call.finish(AuditOutcome.ERROR, HttpStatus.BAD_GATEWAY.value(), ex.getMessage(), null);
            return problem(
                    HttpStatus.BAD_GATEWAY,
                    "Could not obtain an access token for this credential",
                    call.correlationId,
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
            return problem(HttpStatus.BAD_GATEWAY, "Upstream request failed", call.correlationId, new HttpHeaders());
        } catch (Exception ex) {
            // Logged with its stack trace, never returned: the message can quote internal detail.
            log.warn("Gateway request failed [correlationId={}]", call.correlationId, ex);
            call.finish(AuditOutcome.ERROR, HttpStatus.BAD_GATEWAY.value(), "Upstream request failed", null);
            return problem(HttpStatus.BAD_GATEWAY, "Upstream request failed", call.correlationId, new HttpHeaders());
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

    private ResponseEntity<byte[]> problem(
            HttpStatus status, String detail, String correlationId, HttpHeaders headers) {
        var body = new LinkedHashMap<String, Object>();
        body.put("title", "Gateway request rejected");
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("correlationId", correlationId);
        byte[] payload = mapper.writeValueAsBytes(body);
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        headers.set(CorrelationIdFilter.RESPONSE_HEADER, correlationId);
        return ResponseEntity.status(status).headers(headers).body(payload);
    }

    /** Signals a decision the gateway made itself, as opposed to an upstream failure. */
    static class Denied extends RuntimeException {
        final HttpStatus status;

        Denied(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }
    }
}
