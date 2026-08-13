package io.janus.gateway;

import org.springframework.http.HttpHeaders;

import io.janus.shared.ErrorCode;

/**
 * Signals that Janus refused a call to stay inside an allowance, rather than because the caller was
 * not permitted to make it. It is answered with 429 and always carries a {@code Retry-After}, so a
 * client that simply obeys the header needs no rate-limiting logic of its own.
 *
 * <p>Three different allowances end here and a caller has to tell them apart: its own quota is
 * something it can slow down to fit, whereas the provider's ceiling and a cooldown are shared with
 * every other caller and waiting is all that helps. So each carries its own {@link ErrorCode}.
 */
class Throttled extends RuntimeException {
    final ErrorCode code;
    final long retryAfterSeconds;
    final transient HttpHeaders headers;

    Throttled(ErrorCode code, String message, long retryAfterSeconds, HttpHeaders headers) {
        super(message);
        this.code = code;
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
        this.headers = headers;
    }
}
