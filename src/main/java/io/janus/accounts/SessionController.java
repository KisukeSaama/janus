package io.janus.accounts;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.http.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.*;

/**
 * The console's own session.
 *
 * <p>{@code GET} answers who is signed in, and is also what puts the CSRF cookie in place before
 * anybody has signed in — which is why it is reachable unauthenticated and answers 401 rather than
 * redirecting.
 */
@RestController
@RequestMapping("/api/admin/session")
public class SessionController {
    private final SessionService sessions;

    public SessionController(SessionService sessions) {
        this.sessions = sessions;
    }

    public record Credentials(
            @NotBlank String username, @NotBlank String password) {}

    @GetMapping
    public SessionService.Identity current() {
        return sessions.current();
    }

    @PostMapping
    public SessionService.Identity signIn(
            @Valid @RequestBody Credentials credentials, HttpServletRequest request, HttpServletResponse response) {
        return sessions.signIn(credentials.username(), credentials.password(), request, response);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void signOut(HttpServletRequest request) {
        sessions.signOut(request);
    }

    /** Nobody is signed in. Not an error worth a stack trace, and not a 500. */
    @ExceptionHandler({IllegalStateException.class, AuthenticationException.class})
    ResponseEntity<ProblemDetail> unauthenticated(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Not signed in"));
    }

    @ExceptionHandler(SessionService.TooManyAttemptsException.class)
    ResponseEntity<ProblemDetail> throttled(SessionService.TooManyAttemptsException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage()));
    }
}
