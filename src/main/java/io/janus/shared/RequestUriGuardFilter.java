package io.janus.shared;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses request URIs that different layers would read differently.
 *
 * <p>Tomcat, Spring Security's path matching, and Spring MVC's handler mapping each normalise a URI
 * in their own way. Where they disagree, an authorisation rule can be evaluated against one path
 * while a handler runs on another — the classic shape of a path-confusion bypass. Rather than rely
 * on the three agreeing, anything ambiguous is rejected here, before the first of them looks at it.
 *
 * <p>None of the rejected forms has a legitimate use: an empty path segment, a dot segment, a
 * backslash, or a control character in a URI is always either a mistake or an attempt.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestUriGuardFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (isAmbiguous(uri)) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getOutputStream()
                    .write(
                            ("""
                    {"title":"Bad Request","status":400,"detail":"The request path is not unambiguous"}""")
                                    .getBytes(StandardCharsets.UTF_8));
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isAmbiguous(String uri) {
        if (uri == null || uri.isEmpty()) return true;
        if (uri.contains("//") || uri.contains("\\")) return true;
        for (String segment : uri.split("/", -1)) if (segment.equals(".") || segment.equals("..")) return true;
        // A raw space or control character cannot appear in a well-formed request line; if one
        // reaches this far, some layer has already been lenient about the request.
        for (int i = 0; i < uri.length(); i++) {
            char c = uri.charAt(i);
            if (c <= 0x20 || c == 0x7f) return true;
        }
        return false;
    }
}
