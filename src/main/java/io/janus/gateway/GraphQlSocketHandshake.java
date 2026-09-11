package io.janus.gateway;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.*;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import tools.jackson.databind.ObjectMapper;

import io.janus.audit.AuditOutcome;
import io.janus.audit.AuditService;
import io.janus.gateway.graphql.GraphQlEndpoint;
import io.janus.security.GatewayPrincipal;
import io.janus.shared.ApiProblem;
import io.janus.shared.CorrelationIdFilter;
import io.janus.shared.ErrorCode;

/**
 * Authorises a WebSocket before it is opened, by the same steps a request takes (see
 * {@link GatewayAdmission}), and answers a refusal the way a request's is answered: a problem
 * document with a code, before any socket exists.
 *
 * <p>By the time this runs the caller has been authenticated by the gateway's filter chain, which a
 * handshake passes through like any request. What is decided here is what the handshake asks for: a
 * GraphQL endpoint this application holds a grant on. Each operation the socket then carries is
 * decided separately, as it arrives (see {@link GraphQlSocketHandler}).
 */
@Component
public class GraphQlSocketHandshake implements HandshakeInterceptor {
    private static final String PREFIX = "/gateway/";

    private final GatewayAdmission admission;
    private final GraphQlStreams streams;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public GraphQlSocketHandshake(
            GatewayAdmission admission, GraphQlStreams streams, AuditService audit, ObjectMapper mapper) {
        this.admission = admission;
        this.streams = streams;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler handler,
            Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servlet)) return false;
        var http = servlet.getServletRequest();
        String correlationId = CorrelationIdFilter.current();
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof GatewayPrincipal principal)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        String path = GatewayPath.applicationPath(http);
        UUID providerId = null;
        String decoded = null;
        try {
            if (!path.startsWith(PREFIX))
                throw new GatewayController.Denied(
                        HttpStatus.BAD_REQUEST, ErrorCode.PATH_INVALID, "Unsafe gateway path");
            String rest = path.substring(PREFIX.length());
            int slash = rest.indexOf('/');
            String slug = slash < 0 ? rest : rest.substring(0, slash);
            var route = GatewayPath.parse(path, slug, http.getQueryString());
            decoded = route.decodedPath();

            var provider = admission.provider(slug);
            providerId = provider.getId();
            var grant = admission.grant(principal, provider);
            var scope = grant.getScope();
            // Only GraphQL is relayed over a socket. A general WebSocket proxy would be a tunnel
            // through which nothing Janus reads could be decided on, and it is not what anybody asked
            // for by declaring an endpoint.
            if (!GraphQlEndpoint.matches(provider.getGraphqlPath(), decoded))
                throw new GatewayController.Denied(
                        HttpStatus.BAD_REQUEST,
                        ErrorCode.BAD_REQUEST,
                        "A WebSocket is relayed to this API's GraphQL endpoint and nowhere else");
            // A handshake is a GET. Where the grant names operation types, those decide instead, as
            // they do for a request to the same endpoint, one operation at a time.
            if (!scope.narrowsGraphQl()) admission.checkMethod(scope, HttpMethod.GET);
            admission.checkPath(scope, route);
            admission.checkDestination(provider);
            var pinned = admission.pinned(scope, http.getHeader(GatewayTrafficService.IDENTITY_HEADER));

            var exchange = new GatewayExchange(
                    provider,
                    grant,
                    principal.applicationId(),
                    HttpMethod.GET,
                    route,
                    forwarded(http),
                    null,
                    correlationId,
                    pinned,
                    null);
            var socket = new GraphQlSocket(principal, exchange);
            socket.registration(streams.open(
                    new GraphQlStreams.Admission(
                            principal.applicationId(),
                            grant.getId(),
                            grant.getCredential().getId(),
                            provider.getId()),
                    () -> socket.close(GraphQlSocketHandler.REVOKED)));
            attributes.put(GraphQlSocket.ATTRIBUTE, socket);
            http.setAttribute(GraphQlSocket.ATTRIBUTE, socket);
            response.getHeaders().set(CorrelationIdFilter.RESPONSE_HEADER, correlationId);
            return true;
        } catch (GatewayController.Denied denied) {
            var outcome = denied.status.is5xxServerError() ? AuditOutcome.ERROR : AuditOutcome.DENIED;
            refuse(response, denied.status, denied.code, denied.getMessage(), new HttpHeaders());
            record(principal, outcome, providerId, decoded, denied.status.value(), denied.getMessage(), correlationId);
            return false;
        } catch (Throttled throttled) {
            var headers = throttled.headers;
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(throttled.retryAfterSeconds));
            refuse(response, HttpStatus.TOO_MANY_REQUESTS, throttled.code, throttled.getMessage(), headers);
            record(
                    principal,
                    AuditOutcome.THROTTLED,
                    providerId,
                    decoded,
                    HttpStatus.TOO_MANY_REQUESTS.value(),
                    throttled.getMessage(),
                    correlationId);
            return false;
        }
    }

    /**
     * An upgrade that did not happen after all (a malformed handshake, an unsupported version) leaves
     * a socket that was admitted and never opened. Its place in {@link GraphQlStreams} is released here
     * rather than held until the application's ceiling is reached by sockets that never existed.
     */
    @Override
    public void afterHandshake(
            ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception exception) {
        if (!(request instanceof ServletServerHttpRequest servlet)) return;
        if (!(servlet.getServletRequest().getAttribute(GraphQlSocket.ATTRIBUTE) instanceof GraphQlSocket socket))
            return;
        // Refused rather than upgraded is what the handshake handler writes as a 4xx. Anything else is
        // left alone: whether the container has written the 101 yet is its own business.
        boolean refused = response instanceof ServletServerHttpResponse served
                && served.getServletResponse().getStatus() >= 400;
        if (exception != null || refused) socket.close(GraphQlSocketHandler.UPSTREAM_FAILED);
    }

    /** The handshake's headers, through the same policy as a request's; the socket's own are dropped later. */
    private static HttpHeaders forwarded(HttpServletRequest request) {
        var outbound = new HttpHeaders();
        Collections.list(request.getHeaderNames()).forEach(name -> {
            if (HeaderPolicy.isRequestHeaderForwarded(name))
                outbound.put(name, Collections.list(request.getHeaders(name)));
        });
        return outbound;
    }

    private void refuse(
            ServerHttpResponse response, HttpStatus status, ErrorCode code, String detail, HttpHeaders headers) {
        response.setStatusCode(status);
        response.getHeaders().addAll(headers);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        response.getHeaders().set(ApiProblem.HEADER, code.wire());
        response.getHeaders().set(CorrelationIdFilter.RESPONSE_HEADER, CorrelationIdFilter.current());
        try {
            response.getBody().write(mapper.writeValueAsBytes(ApiProblem.body(status, code, detail)));
        } catch (IOException ex) {
            // The caller went while being refused; the status is already set.
        }
    }

    private void record(
            GatewayPrincipal principal,
            AuditOutcome outcome,
            UUID providerId,
            String path,
            int status,
            String detail,
            String correlationId) {
        audit.recordGateway(new AuditService.GatewayEvent(
                principal.applicationId(),
                principal.ownerId(),
                outcome,
                providerId,
                HttpMethod.GET.name(),
                path,
                status,
                "websocket refused, " + detail,
                correlationId));
    }
}
