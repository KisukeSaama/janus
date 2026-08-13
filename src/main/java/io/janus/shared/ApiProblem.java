package io.janus.shared;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.ObjectMapper;

/**
 * One shape for every refusal Janus writes, wherever it is written from.
 *
 * <p>Four layers can end a request: a filter running before the dispatcher, the gateway controller,
 * the administration controllers, and Spring Security. Each used to assemble its own body, and they
 * had drifted — the same failure looked different depending on which one caught it, and two of them
 * dropped the correlation identifier entirely. A client should be able to write one parser.
 *
 * <p>Every document therefore carries {@code type}, {@code title}, {@code status}, {@code detail},
 * {@code code}, {@code correlationId} and {@code timestamp}; individual failures may add members of
 * their own, as validation does with {@code errors}. The members and their names match what {@link
 * ProblemDetail} produces on the MVC path, so the two are the same document assembled two ways.
 */
public final class ApiProblem {

    /** Repeats {@code code} where a caller can read it without touching the body. */
    public static final String HEADER = "X-Janus-Error";

    private ApiProblem() {}

    /** The document as a plain map, for the layers that serialise it themselves. */
    public static Map<String, Object> body(HttpStatus status, ErrorCode code, String detail) {
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "about:blank");
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("code", code.wire());
        body.put("detail", Objects.requireNonNullElse(detail, status.getReasonPhrase()));
        body.put("correlationId", CorrelationIdFilter.current());
        body.put("timestamp", Instant.now().toString());
        return body;
    }

    /** The same document as the object Spring MVC renders, for the exception handler. */
    public static ProblemDetail detail(HttpStatus status, ErrorCode code, String detail) {
        var problem =
                ProblemDetail.forStatusAndDetail(status, Objects.requireNonNullElse(detail, status.getReasonPhrase()));
        problem.setProperty("code", code.wire());
        problem.setProperty("correlationId", CorrelationIdFilter.current());
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * Writes one straight onto the response, for the filters that run outside the dispatcher and so
     * never reach an exception handler.
     *
     * <p>The correlation identifier is set as a header here as well as in the body. {@link
     * CorrelationIdFilter} has normally set it already, but a filter that calls {@code
     * HttpServletResponse#reset()} to discard a partial response discards that header with it — and
     * the requests that end that way are exactly the ones a caller has nothing else to report.
     */
    public static void write(
            HttpServletResponse response, ObjectMapper mapper, HttpStatus status, ErrorCode code, String detail)
            throws IOException {
        var body = body(status, code, detail);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HEADER, code.wire());
        response.setHeader(CorrelationIdFilter.RESPONSE_HEADER, (String) body.get("correlationId"));
        mapper.writeValue(response.getOutputStream(), body);
    }
}
