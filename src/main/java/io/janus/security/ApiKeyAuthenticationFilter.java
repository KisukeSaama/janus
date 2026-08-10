package io.janus.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.janus.applications.ApplicationRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.*;

@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {
    public static final String APP_HEADER="X-Janus-Application-Id", KEY_HEADER="X-Janus-Api-Key";
    private final ApplicationRepository applications; private final PasswordEncoder encoder; private final ObjectMapper mapper;
    public ApiKeyAuthenticationFilter(ApplicationRepository applications, PasswordEncoder encoder, ObjectMapper mapper) { this.applications=applications; this.encoder=encoder; this.mapper=mapper; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/gateway/"); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        io.janus.applications.Application app;
        String key=request.getHeader(KEY_HEADER);
        try {
            app=applications.findById(UUID.fromString(request.getHeader(APP_HEADER))).orElse(null);
        } catch (RuntimeException ex) { unauthorized(response); return; }
        if (app == null || !app.enabled || key == null || !encoder.matches(key, app.apiKeyHash)) { unauthorized(response); return; }
        var principal = new GatewayPrincipal(app.id, app.name, app.environment);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
        chain.doFilter(request,response);
    }
    private void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value()); response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), Map.of("title","Unauthorized","status",401,"detail","Valid Janus application credentials are required"));
    }
}
