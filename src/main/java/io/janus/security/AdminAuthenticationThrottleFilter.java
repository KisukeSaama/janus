package io.janus.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;

import org.springframework.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import io.janus.audit.AuditAction;
import io.janus.audit.AuditActor;
import io.janus.audit.AuditService;
import io.janus.shared.ApiProblem;
import io.janus.shared.ErrorCode;

/**
 * Rate-limits failed administrator sign-ins over HTTP Basic. Unrestricted guessing is the most
 * direct route to the whole control plane; a blocked client is refused before the password is ever
 * compared.
 *
 * <p>Only requests that actually carried credentials are counted. Since the console signs in with a
 * cookie, most 401s here are expired sessions — a console left open overnight, a tab restored — and
 * counting those would lock an address out for having done nothing at all. The sign-in endpoint
 * throttles itself, on the same store, and knows a refused password when it sees one.
 */
public class AdminAuthenticationThrottleFilter extends OncePerRequestFilter {
    private final AuthenticationThrottle throttle;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public AdminAuthenticationThrottleFilter(AuthenticationThrottle throttle, AuditService audit, ObjectMapper mapper) {
        this.throttle = throttle;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String client = "admin:" + request.getRemoteAddr();
        long blockedFor = throttle.blockedForSeconds(client);
        if (blockedFor > 0) {
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(blockedFor));
            ApiProblem.write(
                    response,
                    mapper,
                    HttpStatus.TOO_MANY_REQUESTS,
                    ErrorCode.AUTHENTICATION_THROTTLED,
                    "Too many failed sign-in attempts");
            return;
        }
        chain.doFilter(request, response);
        boolean presentedCredentials = request.getHeader(HttpHeaders.AUTHORIZATION) != null;
        if (presentedCredentials && response.getStatus() == HttpStatus.UNAUTHORIZED.value()) {
            throttle.recordFailure(client);
            audit.recordAuthenticationDenied(
                    AuditActor.ADMIN,
                    AuditAction.ADMIN_AUTHENTICATION,
                    request.getMethod(),
                    request.getRequestURI(),
                    HttpStatus.UNAUTHORIZED.value(),
                    "Invalid administrator credentials");
        } else if (presentedCredentials) {
            throttle.recordSuccess(client);
        }
    }
}
