package io.janus.gateway;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import org.slf4j.*;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import io.janus.gateway.graphql.GraphQlProperties;
import io.janus.shared.ErrorCode;

/**
 * Every subscription open through this instance, over HTTP or WebSocket, and what it was admitted on.
 *
 * <p>An ordinary call is authorised once and is over a moment later, so a revoked grant only has to
 * stop the next one. A subscription is authorised once and then lasts as long as somebody keeps it
 * open, which could be days. Without this, withdrawing a grant, disabling a service, rotating its key
 * or replacing a secret would leave every stream it had already opened running on the old decision,
 * and "takes effect on the next call" would quietly become "takes effect when the caller hangs up".
 * So each stream is registered here with what admitted it, and every administrative change that
 * reaches {@link TrafficPolicyRegistry} closes the streams it has made unauthorised.
 *
 * <p>Also where the per-application ceiling lives. A stream holds a socket and a thread in this
 * process for as long as it is open, which a request never does, so an application that opens them in
 * a loop would exhaust the instance for everybody. The ceiling is counted atomically per application,
 * and refused with its own code, because the repair is closing a stream rather than waiting.
 */
@Component
public class GraphQlStreams {
    private static final Logger log = LoggerFactory.getLogger(GraphQlStreams.class);

    /** What one open stream was admitted on, and how to end it. */
    public record Admission(UUID applicationId, UUID grantId, UUID credentialId, UUID providerId) {}

    private record Held(Admission admission, Runnable close) {}

    private final GraphQlProperties properties;
    private final AtomicLong ids = new AtomicLong();
    private final ConcurrentHashMap<Long, Held> open = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AtomicInteger> perApplication = new ConcurrentHashMap<>();

    public GraphQlStreams(GraphQlProperties properties) {
        this.properties = properties;
    }

    /**
     * Admits one more stream, or refuses it when the application already holds its share.
     *
     * @param close how to end the stream from outside, which is what a revocation calls; it must be
     *     safe to call more than once and from any thread
     * @return the registration, which the stream releases itself when it ends
     */
    public Registration open(Admission admission, Runnable close) {
        int ceiling = properties.maxStreamsPerApplication();
        var count = perApplication.computeIfAbsent(admission.applicationId(), id -> new AtomicInteger());
        int now = count.incrementAndGet();
        if (ceiling > 0 && now > ceiling) {
            count.decrementAndGet();
            throw new Throttled(
                    ErrorCode.STREAM_LIMIT,
                    "This application already holds " + ceiling + " open subscriptions",
                    // Nothing frees up with time: a stream ends when somebody closes it. A second is
                    // what the header needs to say something, and the code says the rest.
                    1,
                    new HttpHeaders());
        }
        long id = ids.incrementAndGet();
        open.put(id, new Held(admission, close));
        return new Registration(id, admission.applicationId());
    }

    /** Held by the stream; released exactly once, however it ends. */
    public final class Registration implements AutoCloseable {
        private final long id;
        private final UUID applicationId;
        private boolean released;

        private Registration(long id, UUID applicationId) {
            this.id = id;
            this.applicationId = applicationId;
        }

        @Override
        public synchronized void close() {
            if (released) return;
            released = true;
            if (open.remove(id) == null) return;
            var count = perApplication.get(applicationId);
            if (count != null && count.decrementAndGet() <= 0) perApplication.remove(applicationId, count);
        }
    }

    public int closeGrant(UUID grantId) {
        return closeWhere(admission -> admission.grantId().equals(grantId));
    }

    public int closeCredential(UUID credentialId) {
        return closeWhere(admission -> admission.credentialId().equals(credentialId));
    }

    public int closeProvider(UUID providerId) {
        return closeWhere(admission -> admission.providerId().equals(providerId));
    }

    public int closeApplication(UUID applicationId) {
        return closeWhere(admission -> admission.applicationId().equals(applicationId));
    }

    /** How many streams are open right now. */
    public int active() {
        return open.size();
    }

    private int closeWhere(Predicate<Admission> matches) {
        int closed = 0;
        for (var held : List.copyOf(open.values())) {
            if (!matches.test(held.admission())) continue;
            try {
                held.close().run();
                closed++;
            } catch (RuntimeException ex) {
                // One stream refusing to close must not leave the others open on a revoked decision.
                log.warn("Could not close a revoked subscription", ex);
            }
        }
        return closed;
    }
}
