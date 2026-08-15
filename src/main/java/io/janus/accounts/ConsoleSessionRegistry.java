package io.janus.accounts;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The console sessions currently open, indexed by whom they belong to.
 *
 * <p>Kept so that changing a password can end them. Without it a session cookie outlives the
 * password it was obtained with, for as long as the container keeps the session: somebody who
 * changes their password because they believe it was learned has done the one thing they know to do
 * and has closed nothing at all, since whoever learned it may already be signed in.
 *
 * <p>Written by hand rather than through Spring Security's {@code SessionRegistry}, which is
 * populated by the authentication filters this console deliberately does not use — see
 * {@link SessionService} — and whose {@code expireNow} only marks a session for the concurrency
 * filter to notice later. Here the session is invalidated outright, which is what "ended" has to
 * mean for this to be worth doing.
 *
 * <p>Per instance, like every other memory in Janus. A deployment behind more than one replica needs
 * shared session storage before this means anything across all of them, and it already needs that
 * for sessions to work at all.
 */
@Component
public class ConsoleSessionRegistry implements HttpSessionListener {
    /** Account to its live sessions, and the reverse, so the container's own notice can undo both. */
    private final Map<UUID, Map<String, HttpSession>> byAccount = new ConcurrentHashMap<>();

    private final Map<String, UUID> holders = new ConcurrentHashMap<>();

    /** Records a session against the account that has just signed in on it. */
    public void opened(UUID accountId, HttpSession session) {
        byAccount.computeIfAbsent(accountId, id -> new ConcurrentHashMap<>()).put(session.getId(), session);
        holders.put(session.getId(), accountId);
    }

    /**
     * Ends every session this account holds, except the one the request is being made on.
     *
     * <p>The exception is what keeps somebody from signing themselves out by changing their own
     * password. When an administrator resets somebody else's, the caller's session belongs to the
     * caller and none of the target's are spared, which is the intended reading of a reset.
     *
     * @return how many were ended
     */
    public int endOthers(UUID accountId) {
        var sessions = byAccount.get(accountId);
        if (sessions == null) return 0;
        String keep = currentSessionId();
        int ended = 0;
        for (var open : List.copyOf(sessions.entrySet())) {
            if (open.getKey().equals(keep)) continue;
            forget(accountId, open.getKey());
            try {
                open.getValue().invalidate();
                ended++;
            } catch (IllegalStateException alreadyEnded) {
                // The container had already ended it. Nothing to do, and nothing worth reporting.
            }
        }
        return ended;
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        UUID accountId = holders.get(event.getSession().getId());
        if (accountId != null) forget(accountId, event.getSession().getId());
    }

    private void forget(UUID accountId, String sessionId) {
        holders.remove(sessionId);
        var sessions = byAccount.get(accountId);
        if (sessions == null) return;
        sessions.remove(sessionId);
        if (sessions.isEmpty()) byAccount.remove(accountId, sessions);
    }

    /**
     * The session this request is being made on, or null when there is none — which is the case for
     * a script signing in with Basic authentication, and means nothing is spared.
     */
    private static String currentSessionId() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servlet)) return null;
        var session = servlet.getRequest().getSession(false);
        return session == null ? null : session.getId();
    }
}
