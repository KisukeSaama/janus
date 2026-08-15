package io.janus.accounts;

import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.*;
import org.springframework.security.web.context.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.janus.audit.*;

/**
 * Signing in, and signing out.
 *
 * <p>Written by hand rather than left to {@code formLogin}, because three things have to happen in a
 * particular order and each one is a classic way to get this wrong:
 *
 * <ol>
 *   <li>The session identifier is changed <em>before</em> the context is written, so a session
 *       fixed by an attacker before the sign-in is not the one that ends up authenticated.
 *   <li>The context is handed to the {@link SecurityContextRepository}. Since Spring Security 6,
 *       {@code SecurityContextHolder.setContext} persists nothing on its own — forget this and the
 *       sign-in appears to work, then the very next request is a 401.
 *   <li>The holder is set as well, so the journal can attribute the sign-in that just happened.
 * </ol>
 *
 * <p>Throttling lives here rather than in a filter: this endpoint is the one that takes a password,
 * so it is the one that knows the difference between a refused password and an expired session.
 */
@Service
public class SessionService {
    private final AuthenticationManager authentication;
    private final AccountRepository accounts;
    private final AccessScope scope;
    private final SecurityContextRepository contexts = new HttpSessionSecurityContextRepository();
    private final io.janus.security.AuthenticationThrottle throttle;
    private final ConsoleSessionRegistry sessions;
    private final AuditService audit;

    public SessionService(
            AuthenticationManager authentication,
            AccountRepository accounts,
            AccessScope scope,
            io.janus.security.AuthenticationThrottle throttle,
            ConsoleSessionRegistry sessions,
            AuditService audit) {
        this.authentication = authentication;
        this.accounts = accounts;
        this.scope = scope;
        this.throttle = throttle;
        this.sessions = sessions;
        this.audit = audit;
    }

    /** Who is signed in, as the console needs to know them. */
    public record Identity(java.util.UUID id, String username, String displayName, AccountRole role) {
        static Identity of(ConsoleUser user) {
            return new Identity(user.id(), user.getUsername(), user.displayName(), user.role());
        }
    }

    @Transactional
    public Identity signIn(String username, String password, HttpServletRequest request, HttpServletResponse response) {
        // Keyed by address, not by username: locking an account by name hands anybody a denial of
        // service against a colleague whose login they happen to know.
        String client = "admin:" + request.getRemoteAddr();
        if (throttle.isBlocked(client)) throw new TooManyAttemptsException();

        Authentication result;
        try {
            result = authentication.authenticate(new UsernamePasswordAuthenticationToken(username, password));
        } catch (AuthenticationException ex) {
            throttle.recordFailure(client);
            // Disabled, unknown, wrong password: one answer for all three, so this is not a way to
            // find out which usernames exist.
            audit.recordAuthenticationDenied(
                    AuditActor.ADMIN,
                    AuditAction.ADMIN_AUTHENTICATION,
                    request.getMethod(),
                    request.getRequestURI(),
                    401,
                    "Invalid console credentials");
            throw new BadCredentialsException("Invalid credentials");
        }

        throttle.recordSuccess(client);
        var existing = request.getSession(false);
        if (existing != null) existing.invalidate();
        var session = request.getSession(true);

        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(result);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);

        var user = (ConsoleUser) result.getPrincipal();
        // Written down here because this is the only place a console session is created, and the
        // authentication filters that would otherwise record it are the ones this method replaces.
        sessions.opened(user.id(), session);
        accounts.markSignedIn(user.id(), Instant.now());
        audit.recordAdmin(AuditAction.ACCOUNT_SIGNED_IN, null, user.getUsername());
        return Identity.of(user);
    }

    /** Ends the session on the server, so a cookie kept by a browser stands for nothing. */
    public void signOut(HttpServletRequest request) {
        scope.signedIn().ifPresent(user -> audit.recordAdmin(AuditAction.ACCOUNT_SIGNED_OUT, null, user.getUsername()));
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
    }

    public Identity current() {
        return Identity.of(scope.current());
    }

    /** Answered as 429 rather than 401: the credentials were never looked at. */
    public static class TooManyAttemptsException extends RuntimeException {
        public TooManyAttemptsException() {
            super("Too many failed sign-in attempts. Wait before trying again.");
        }
    }
}
