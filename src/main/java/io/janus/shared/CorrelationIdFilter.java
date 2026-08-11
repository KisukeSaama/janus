package io.janus.shared;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes one correlation identifier per request. A caller-supplied {@code X-Correlation-Id} is
 * honoured only when it matches a conservative charset, so it can never be used to inject content
 * into logs, audit records, or response headers.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CorrelationIdFilter extends OncePerRequestFilter {
    public static final String REQUEST_HEADER = "X-Correlation-Id";
    public static final String RESPONSE_HEADER = "X-Janus-Correlation-Id";
    public static final String ATTRIBUTE = "janus.correlationId";
    public static final String MDC_KEY = "correlationId";
    private static final java.util.regex.Pattern SAFE = java.util.regex.Pattern.compile("[A-Za-z0-9._-]{1,80}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader(REQUEST_HEADER);
        String correlationId = supplied != null && SAFE.matcher(supplied).matches()
                ? supplied
                : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, correlationId);
        response.setHeader(RESPONSE_HEADER, correlationId);
        MDC.put(MDC_KEY, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** Current request's correlation identifier, or a fresh one when called outside a request. */
    public static String current() {
        String fromMdc = MDC.get(MDC_KEY);
        return fromMdc != null ? fromMdc : UUID.randomUUID().toString();
    }
}
