package io.janus.security;

import java.io.IOException;
import java.util.LinkedHashMap;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import io.janus.gateway.RateLimiter;
import io.janus.shared.CorrelationIdFilter;

/**
 * A ceiling on how fast one client may call Janus at all, whoever it turns out to be.
 *
 * <p>The other two limiters answer different questions. {@link AuthenticationThrottle} counts
 * failures, so a caller presenting a valid key — or one hammering an endpoint that answers 400 —
 * is never slowed by it. The per-grant and per-provider buckets are policy, applied after a request
 * has been authenticated and matched to a grant, and a deployment that configures none has no
 * ceiling at all. This filter runs before both, refuses on volume rather than on outcome, and needs
 * nothing configured to be in force.
 *
 * <p>It duplicates what nginx already does per IP, deliberately. The reverse proxy is the only thing
 * enforcing a rate today, so any path that reaches the backend directly — a port published during
 * development, a second service on the internal network, a future ingress — arrives unmetered.
 *
 * <p>The token exchange is metered here too, and on the console's allowance rather than the
 * gateway's. An access token lasts fifteen minutes, so a client that behaves asks for one rarely;
 * a client that does not costs a database write per exchange and an audit row with it, and the
 * failure throttle above never sees it because nothing it presents is wrong.
 *
 * <p>Refusals are counted, never audited and never logged per request: writing a row or a line for
 * each rejected call would turn a flood into a second flood against the database and the log, which
 * is the outcome the filter exists to prevent.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ClientRateLimitFilter extends OncePerRequestFilter {
    private static final String COUNTER = "janus.ratelimit.rejected";

    /** Buckets of its own, not the gateway's: 50k spoofed-source keys must not evict a grant's quota. */
    private final RateLimiter limiter = new RateLimiter();

    private final int adminPerMinute;
    private final int adminBurst;
    private final int gatewayPerMinute;
    private final int gatewayBurst;
    private final MeterRegistry registry;
    private final ObjectMapper mapper;

    public ClientRateLimitFilter(
            @Value("${janus.security.rate-limit.admin-per-minute:300}") int adminPerMinute,
            @Value("${janus.security.rate-limit.admin-burst:60}") int adminBurst,
            @Value("${janus.security.rate-limit.gateway-per-minute:1800}") int gatewayPerMinute,
            @Value("${janus.security.rate-limit.gateway-burst:300}") int gatewayBurst,
            MeterRegistry registry,
            ObjectMapper mapper) {
        this.adminPerMinute = adminPerMinute;
        this.adminBurst = adminBurst;
        this.gatewayPerMinute = gatewayPerMinute;
        this.gatewayBurst = gatewayBurst;
        this.registry = registry;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean gateway = request.getRequestURI().startsWith("/gateway/");
        String surface = gateway ? "gateway" : request.getRequestURI().startsWith("/oauth/") ? "oauth" : "admin";
        int perMinute = gateway ? gatewayPerMinute : adminPerMinute;
        int burst = gateway ? gatewayBurst : adminBurst;

        // getRemoteAddr() is the address RemoteIpValve settled on, which a caller cannot choose:
        // see server.forward-headers-strategy. Were it spoofable, every ceiling here would be too.
        String key = surface + ":" + request.getRemoteAddr();
        var decision = limiter.tryAcquire(key, perMinute, burst);
        if (!decision.allowed()) {
            Counter.builder(COUNTER).tag("surface", surface).register(registry).increment();
            refuse(response, decision.retryAfterSeconds());
            return;
        }
        chain.doFilter(request, response);
    }

    /** Static assets are served by nginx, never by the backend, so only its own surfaces are metered. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // Health is how an orchestrator decides whether to keep the container: refusing it under
        // load would restart the instance the flood is aimed at.
        if (uri.startsWith("/actuator/health")) return true;
        return !uri.startsWith("/gateway/")
                && !uri.startsWith("/api/")
                && !uri.startsWith("/oauth/")
                && !uri.startsWith("/actuator/");
    }

    private void refuse(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        if (response.isCommitted()) return;
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        var body = new LinkedHashMap<String, Object>();
        body.put("title", HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase());
        body.put("status", HttpStatus.TOO_MANY_REQUESTS.value());
        body.put("detail", "Too many requests from this client");
        body.put("correlationId", CorrelationIdFilter.current());
        mapper.writeValue(response.getOutputStream(), body);
    }
}
