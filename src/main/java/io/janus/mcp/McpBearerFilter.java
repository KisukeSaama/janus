package io.janus.mcp;

import java.io.IOException;
import java.net.URI;
import java.util.*;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import io.janus.accounts.ActingAssistant;
import io.janus.shared.ApiProblem;
import io.janus.shared.ErrorCode;

/**
 * Turns an MCP access token into the person it acts for.
 *
 * <p>Not a component, on purpose: a filter Spring finds on its own is also registered with the servlet
 * container and runs on every request, outside the chain that was meant to scope it. The MCP chain
 * builds this one and it runs there only.
 *
 * <p>It also refuses a request carrying a foreign {@code Origin}, which the transport specification
 * requires of every server: a page on another site could otherwise reach a server listening on
 * localhost through the visitor's browser. Native clients send no origin and are unaffected.
 */
public class McpBearerFilter extends OncePerRequestFilter {
    private final McpOAuthService oauth;
    private final Set<String> allowedOrigins;

    public McpBearerFilter(McpOAuthService oauth, Collection<String> allowedOrigins) {
        this.oauth = oauth;
        var origins = new HashSet<String>(allowedOrigins);
        origins.add(origin(oauth.issuer()));
        this.allowedOrigins = Set.copyOf(origins);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !allowedOrigins.contains(origin)) {
            response.sendError(HttpStatus.FORBIDDEN.value());
            return;
        }

        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            // A token that is presented and refused is left unauthenticated here, and answered by the
            // entry point below, which is where the challenge the client needs is written.
            oauth.authenticate(authorization.substring(7).trim()).ifPresent(caller -> {
                var authentication = UsernamePasswordAuthenticationToken.authenticated(
                        caller.user(), null, caller.user().getAuthorities());
                authentication.setDetails(new ActingAssistant(caller.connectionId(), caller.clientName()));
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            });
        }
        chain.doFilter(request, response);
    }

    private static String origin(String url) {
        var uri = URI.create(url);
        return uri.getScheme() + "://" + uri.getRawAuthority();
    }

    /**
     * The answer to a missing or refused token: 401 with the challenge RFC 9728 defines, which is how
     * an MCP client discovers where to authorise before it has ever been told.
     */
    public static AuthenticationEntryPoint entryPoint(McpOAuthService oauth, ObjectMapper mapper) {
        String metadata = oauth.issuer() + "/.well-known/oauth-protected-resource/mcp";
        return (HttpServletRequest request, HttpServletResponse response, AuthenticationException failure) -> {
            boolean presented = request.getHeader(HttpHeaders.AUTHORIZATION) != null;
            String challenge = "Bearer resource_metadata=\"" + metadata + "\", scope=\"" + McpOAuthService.SCOPE + "\""
                    + (presented ? ", error=\"invalid_token\"" : "");
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
            ApiProblem.write(
                    response,
                    mapper,
                    HttpStatus.UNAUTHORIZED,
                    ErrorCode.AUTHENTICATION_REQUIRED,
                    presented ? "The access token is invalid or has expired" : "An access token is required");
        };
    }
}
