package io.janus.shared;

import java.util.*;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolationException;

import org.slf4j.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.exc.InvalidFormatException;

import io.janus.gateway.GatewayRequestSizeFilter.PayloadTooLargeException;

/**
 * Translates failures into RFC 9457 problem responses. Client mistakes are answered precisely;
 * anything unexpected is logged with its correlation identifier and answered generically, so an
 * internal message never becomes part of the API surface.
 *
 * <p>Each answer carries an {@link ErrorCode}, and it is the code rather than the sentence that a
 * client should read: {@code detail} is written for whoever is looking at it and may be reworded at
 * any time. {@link ApiProblem} assembles the document, so what a controller returns and what a
 * filter writes have the same members under the same names.
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(NotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> noResource(NoResourceFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "The requested resource does not exist");
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ResponseEntity<ProblemDetail> badRequest(RuntimeException ex) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException ex) {
        var detail = base(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Request validation failed");
        detail.setProperty(
                "errors",
                ex.getBindingResult().getFieldErrors().stream()
                        .collect(Collectors.toMap(
                                org.springframework.validation.FieldError::getField,
                                error -> Objects.requireNonNullElse(error.getDefaultMessage(), "invalid"),
                                (a, b) -> a)));
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, detail);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> constraint(ConstraintViolationException ex) {
        var detail = base(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Request validation failed");
        detail.setProperty(
                "errors",
                ex.getConstraintViolations().stream()
                        .collect(Collectors.toMap(
                                violation -> violation.getPropertyPath().toString(),
                                jakarta.validation.ConstraintViolation::getMessage,
                                (a, b) -> a)));
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, detail);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> typeMismatch(MethodArgumentTypeMismatchException ex) {
        return problem(
                HttpStatus.BAD_REQUEST,
                ErrorCode.VALIDATION_FAILED,
                "Parameter '" + ex.getName() + "' has an unusable value");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ProblemDetail> missingParameter(MissingServletRequestParameterException ex) {
        return problem(
                HttpStatus.BAD_REQUEST,
                ErrorCode.VALIDATION_FAILED,
                "Parameter '" + ex.getParameterName() + "' is required");
    }

    /**
     * A body the parser refused.
     *
     * <p>Jackson 3 refuses a missing primitive outright instead of defaulting it, so a payload that
     * merely omits {@code enabled} arrives here rather than at bean validation. Answering "not valid
     * JSON" for well-formed JSON sends a client looking for a syntax error that does not exist, so
     * when the parser names the field, it is reported the same way a validation failure is — same
     * code, same {@code errors} member. Only the field's name travels back: the parser's own message
     * quotes the value it choked on.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException ex) {
        log.debug("Rejected unreadable request body", ex);
        String field = offendingField(ex.getCause());
        if (field == null)
            return problem(
                    HttpStatus.BAD_REQUEST,
                    ErrorCode.MALFORMED_BODY,
                    "The request body is not valid JSON for this endpoint");

        var detail = base(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Request validation failed");
        detail.setProperty(
                "errors",
                Map.of(
                        field,
                        ex.getCause() instanceof InvalidFormatException ? "has an unusable value" : "is required"));
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, detail);
    }

    /** The dotted path Jackson was reading when it gave up, or null when it cannot name one. */
    private static String offendingField(Throwable cause) {
        if (!(cause instanceof DatabindException databind)) return null;
        String path = databind.getPath().stream()
                .map(DatabindException.Reference::getPropertyName)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("."));
        return path.isEmpty() ? null : path;
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> methodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return problem(
                HttpStatus.METHOD_NOT_ALLOWED,
                ErrorCode.METHOD_NOT_SUPPORTED,
                "This endpoint does not support " + ex.getMethod());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> mediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        return problem(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "This endpoint requires application/json");
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    ResponseEntity<ProblemDetail> tooLarge(PayloadTooLargeException ex) {
        return problem(
                HttpStatus.PAYLOAD_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE, "Request body exceeds the configured limit");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> conflict(DataIntegrityViolationException ex) {
        log.debug("Rejected conflicting write", ex);
        return problem(
                HttpStatus.CONFLICT,
                ErrorCode.CONFLICT,
                "The record is still referenced or conflicts with an existing record");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception ex) {
        log.error("Unhandled failure [correlationId={}]", CorrelationIdFilter.current(), ex);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "The request could not be completed");
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, ErrorCode code, String message) {
        return respond(status, code, base(status, code, message));
    }

    /** One exit, so the header and the body's {@code code} can never disagree. */
    private ResponseEntity<ProblemDetail> respond(HttpStatus status, ErrorCode code, ProblemDetail detail) {
        return ResponseEntity.status(status)
                .header(ApiProblem.HEADER, code.wire())
                .body(detail);
    }

    private ProblemDetail base(HttpStatus status, ErrorCode code, String message) {
        return ApiProblem.detail(status, code, message);
    }
}
