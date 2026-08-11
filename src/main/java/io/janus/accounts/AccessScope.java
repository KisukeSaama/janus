package io.janus.accounts;

import java.util.*;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Who is asking, and therefore what they may see.
 *
 * <p>Every console query is written to take an owner. This class is the only thing that decides
 * which one a request gets, and services ask it rather than trusting a controller to pass the right
 * value — a controller that forgets is a controller that leaks somebody else's records.
 *
 * <p>{@link #ownerFilter()} is <em>always</em> the caller's own account, whatever their role. There
 * is no supervising view: an administrator exists to say who may sign in, and that is the whole of
 * the difference. Everything a person did not register — a service, an API, a secret, an access
 * rule, the journal entries about them, the traffic they caused — belongs to somebody else and is
 * not theirs to read.
 *
 * <p><strong>It fails closed.</strong> An unauthenticated caller reaching a scoped query is a bug,
 * and answering it unfiltered would turn the separation into decoration. Janus's own paths — the
 * expiry sweep, the gateway — do not come through here; they use finders that say in their names
 * that they are unscoped.
 */
@Component
public class AccessScope {

    /** The owner whose records the caller is entitled to, which is always themselves. */
    public UUID ownerFilter() {
        return current().id();
    }

    public UUID accountId() {
        return current().id();
    }

    public AccountRole role() {
        return current().role();
    }

    /** The signed-in person, or nothing when the caller is Janus itself. */
    public Optional<ConsoleUser> signedIn() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) return Optional.empty();
        return authentication.getPrincipal() instanceof ConsoleUser user ? Optional.of(user) : Optional.empty();
    }

    public ConsoleUser current() {
        return signedIn()
                .orElseThrow(() -> new IllegalStateException(
                        "No signed-in account on this thread; a scoped query cannot be answered"));
    }
}
