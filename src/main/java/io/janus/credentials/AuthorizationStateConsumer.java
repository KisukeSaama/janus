package io.janus.credentials;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes an authorisation state in a transaction of its own.
 *
 * <p>A separate bean for the same reason {@code AuditEventWriter} is one. The callback deletes the
 * state before it does anything else, precisely so that an exchange which fails cannot be retried
 * against it — and then reports each of those failures by throwing, which used to roll the deletion
 * back with everything else. The state stayed valid for the rest of its fifteen minutes, on an
 * endpoint that is necessarily unauthenticated: a browser returning from another site carries no
 * session cookie, so the state is the only thing the callback is judged on.
 *
 * <p>A second transaction rather than {@code noRollbackFor} on the callback, because the callback
 * fails in a good many ways and most of them are not this class's to interpret. It is safe here for
 * the reason it is not safe in {@code OAuthTokenService.refresh}: the caller has only read this row
 * and holds no lock on it, so suspending the caller leaves nothing for the new transaction to wait
 * on.
 */
@Component
class AuthorizationStateConsumer {
    private final AuthorizationStateRepository states;

    AuthorizationStateConsumer(AuthorizationStateRepository states) {
        this.states = states;
    }

    /** Whether this call is the one that used it up. False means somebody else already had it. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean consume(String state) {
        return states.consume(state) == 1;
    }
}
