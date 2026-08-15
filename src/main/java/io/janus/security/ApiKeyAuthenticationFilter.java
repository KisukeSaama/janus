package io.janus.security;

import java.io.IOException;
import java.util.*;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;

import org.springframework.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import io.janus.audit.AuditAction;
import io.janus.audit.AuditActor;
import io.janus.audit.AuditService;
import io.janus.oauth.AccessTokenStore;
import io.janus.shared.ApiProblem;
import io.janus.shared.ErrorCode;

/**
 * Authenticates gateway callers, two ways.
 *
 * <ul>
 *   <li>{@code Authorization: Bearer …} — a token obtained from {@code /oauth/token}. What a browser
 *       page, a mobile app or any SDK will use, because it is what every other API asks for.
 *   <li>{@code X-Janus-Application-Id} + {@code X-Janus-Api-Key} — the long-lived key presented
 *       directly. Kept because a cron job or a one-line {@code curl} should not have to implement an
 *       exchange to make one call.
 * </ul>
 *
 * <p>The bearer path is tried first and is cheap: a lookup in memory, no hashing. Both end at the
 * same principal, so nothing downstream knows or cares which door was used.
 *
 * <p>Three properties matter here. Verified keys are cached, because a BCrypt cost-12 comparison per
 * proxied request is both a latency and a denial-of-service problem. An unknown application still
 * pays a comparison against a fixed hash, so response timing does not disclose which identifiers
 * exist. Repeated failures from one client are throttled, and every rejection is audited.
 */
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {
    public static final String APP_HEADER = "X-Janus-Application-Id";
    public static final String KEY_HEADER = "X-Janus-Api-Key";

    private final ApplicationAuthenticator authenticator;
    private final AccessTokenStore accessTokens;
    private final AuthenticationThrottle throttle;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public ApiKeyAuthenticationFilter(
            ApplicationAuthenticator authenticator,
            AccessTokenStore accessTokens,
            AuthenticationThrottle throttle,
            AuditService audit,
            ObjectMapper mapper) {
        this.authenticator = authenticator;
        this.accessTokens = accessTokens;
        this.throttle = throttle;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !io.janus.gateway.GatewayPath.applicationPath(request).startsWith("/gateway/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String client = request.getRemoteAddr();
        long blockedFor = throttle.blockedForSeconds(client);
        if (blockedFor > 0) {
            // Blocked for a quarter of an hour by default. Said out loud, because a caller that is
            // not told reads this as "my key is wrong" and either goes looking for a fault that is
            // not there or keeps retrying — which is the behaviour the block exists to stop.
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(blockedFor));
            reject(
                    request,
                    response,
                    HttpStatus.TOO_MANY_REQUESTS,
                    ErrorCode.AUTHENTICATION_THROTTLED,
                    "Too many failed authentication attempts",
                    false);
            return;
        }

        var principal = bearer(request).orElseGet(() -> apiKey(request));
        // A page at an origin this service has not declared holds credentials it should not have.
        // Refused as an authentication failure rather than a routing one: from here, a token
        // presented from the wrong place is a token that does not work.
        if (principal != null && !principal.allowsOrigin(request.getHeader(HttpHeaders.ORIGIN))) principal = null;
        if (principal == null) {
            throttle.recordFailure(client);
            reject(
                    request,
                    response,
                    HttpStatus.UNAUTHORIZED,
                    ErrorCode.AUTHENTICATION_REQUIRED,
                    "Valid Janus application credentials are required",
                    true);
            return;
        }

        throttle.recordSuccess(client);
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** A token from the exchange. Resolved in memory: no hash comparison, nothing to throttle. */
    private Optional<GatewayPrincipal> bearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return Optional.empty();
        return accessTokens.resolve(header.substring(7).trim());
    }

    /** The long-lived key, presented directly. Null rather than empty, to keep the caller readable. */
    private GatewayPrincipal apiKey(HttpServletRequest request) {
        UUID applicationId = parseUuid(request.getHeader(APP_HEADER));
        String presentedKey = request.getHeader(KEY_HEADER);
        if (applicationId == null || presentedKey == null || presentedKey.isEmpty()) return null;
        return authenticator.authenticate(applicationId, presentedKey).orElse(null);
    }

    private static UUID parseUuid(String value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void reject(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            ErrorCode code,
            String detail,
            boolean recordAudit)
            throws IOException {
        if (recordAudit) {
            audit.recordAuthenticationDenied(
                    AuditActor.APPLICATION,
                    AuditAction.GATEWAY_AUTHENTICATION,
                    request.getMethod(),
                    request.getRequestURI(),
                    status.value(),
                    detail);
        }
        ApiProblem.write(response, mapper, status, code, detail);
    }
}
