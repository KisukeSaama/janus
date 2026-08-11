package io.janus.gateway;

import org.springframework.http.HttpHeaders;

/**
 * Signals that Janus refused a call to stay inside an allowance, rather than because the caller was
 * not permitted to make it. It is answered with 429 and always carries a {@code Retry-After}, so a
 * client that simply obeys the header needs no rate-limiting logic of its own.
 */
class Throttled extends RuntimeException {
    final long retryAfterSeconds;
    final transient HttpHeaders headers;

    Throttled(String message, long retryAfterSeconds, HttpHeaders headers) {
        super(message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
        this.headers = headers;
    }
}
