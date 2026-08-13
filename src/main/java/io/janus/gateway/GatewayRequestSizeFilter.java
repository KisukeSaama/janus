package io.janus.gateway;

import java.io.IOException;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import io.janus.shared.ApiProblem;
import io.janus.shared.ErrorCode;

/**
 * Bounds the size of a proxied request body. Spring materialises the body as a byte array before the
 * controller runs, so without this limit a single caller could force the process to buffer an
 * arbitrary amount of memory. A declared Content-Length is rejected outright; a chunked body is cut
 * off while it is being read.
 */
@Component
public class GatewayRequestSizeFilter extends OncePerRequestFilter {

    /** Raised while reading an oversized body; surfaced as 413 by the API exception handler. */
    public static class PayloadTooLargeException extends RuntimeException {
        public PayloadTooLargeException(String message) {
            super(message);
        }
    }

    private final long maxRequestBytes;
    private final ObjectMapper mapper;

    public GatewayRequestSizeFilter(
            @Value("${janus.gateway.max-request-bytes:10485760}") long maxRequestBytes, ObjectMapper mapper) {
        this.maxRequestBytes = maxRequestBytes;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith("/gateway/") && !uri.matches("^/[^/]+/gateway/.*");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxRequestBytes) {
            refuse(response);
            return;
        }
        try {
            chain.doFilter(new LimitedRequest(request, maxRequestBytes), response);
        } catch (PayloadTooLargeException ex) {
            // Raised while the body was being read, which happens outside the dispatcher on some paths.
            refuse(response);
        }
    }

    /**
     * The {@code reset()} discards whatever a handler had already written before the body ran out of
     * room — and, with it, every header set so far, including the correlation identifier. {@link
     * ApiProblem} puts that one back, which is why the reset happens before the document is written
     * rather than after.
     */
    private void refuse(HttpServletResponse response) throws IOException {
        if (response.isCommitted()) return;
        response.reset();
        ApiProblem.write(
                response,
                mapper,
                HttpStatus.PAYLOAD_TOO_LARGE,
                ErrorCode.PAYLOAD_TOO_LARGE,
                "Request body exceeds the configured limit");
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private final long limit;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) stream = new LimitedStream(super.getInputStream(), limit);
            return stream;
        }
    }

    private static final class LimitedStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long limit;
        private long consumed;

        LimitedStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        private void count(int read) {
            if (read <= 0) return;
            consumed += read;
            if (consumed > limit) throw new PayloadTooLargeException("Request body exceeds the configured limit");
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            count(value < 0 ? 0 : 1);
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = delegate.read(b, off, len);
            count(read);
            return read;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }
    }
}
