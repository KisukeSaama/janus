package io.janus.gateway;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

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

    public GatewayRequestSizeFilter(@Value("${janus.gateway.max-request-bytes:10485760}") long maxRequestBytes) {
        this.maxRequestBytes = maxRequestBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/gateway/");
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

    private void refuse(HttpServletResponse response) throws IOException {
        if (response.isCommitted()) return;
        response.reset();
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getOutputStream()
                .write(
                        ("""
                {"title":"Payload Too Large","status":413,"detail":"Request body exceeds the configured limit"}""")
                                .getBytes(StandardCharsets.UTF_8));
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
