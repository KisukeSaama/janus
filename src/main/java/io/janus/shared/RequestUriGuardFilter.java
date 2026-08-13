package io.janus.shared;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

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
 *
 * <p>It runs second, behind {@link CorrelationIdFilter} alone, so that its refusals carry an
 * identifier like every other one. That filter reads no path and decides nothing, so nothing it does
 * can be confused by the very URIs this one exists to reject.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestUriGuardFilter extends OncePerRequestFilter {
    private final ObjectMapper mapper;

    public RequestUriGuardFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (isAmbiguous(uri)) {
            ApiProblem.write(
                    response,
                    mapper,
                    HttpStatus.BAD_REQUEST,
                    ErrorCode.PATH_AMBIGUOUS,
                    "The request path is not unambiguous");
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
