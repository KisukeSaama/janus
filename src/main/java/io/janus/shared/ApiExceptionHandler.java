package io.janus.shared;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import java.time.Instant;
import java.util.*;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(NotFoundException ex) { return problem(HttpStatus.NOT_FOUND, ex.getMessage()); }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ResponseEntity<ProblemDetail> badRequest(RuntimeException ex) { return problem(HttpStatus.BAD_REQUEST, ex.getMessage()); }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException ex) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        detail.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
                .collect(java.util.stream.Collectors.toMap(e -> e.getField(), e -> Objects.requireNonNullElse(e.getDefaultMessage(), "invalid"), (a,b) -> a)));
        detail.setProperty("timestamp", Instant.now());
        return ResponseEntity.badRequest().body(detail);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> conflict(DataIntegrityViolationException ex) { return problem(HttpStatus.CONFLICT, "The record is still referenced or conflicts with an existing record"); }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String message) {
        var detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(status).body(detail);
    }
}
