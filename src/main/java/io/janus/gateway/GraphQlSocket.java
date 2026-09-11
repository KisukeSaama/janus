package io.janus.gateway;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import io.janus.security.GatewayPrincipal;

/**
 * One relayed GraphQL WebSocket: the caller's socket, the upstream's, and what admitted the pair.
 *
 * <p>Created at the handshake, once the connection has been authorised, and attached to the socket
 * the container opens. Everything that can end it (the caller, the upstream, a timeout, a revocation)
 * ends it through {@link #close}, which is safe to call from any thread and more than once, and
 * which always releases the stream's place in {@link GraphQlStreams}.
 */
final class GraphQlSocket {
    static final String ATTRIBUTE = GraphQlSocket.class.getName();

    private final GatewayPrincipal principal;
    private final GatewayExchange exchange;
    private final Set<String> operations = ConcurrentHashMap.newKeySet();
    private final Sinks.Many<String> toUpstream = Sinks.many().unicast().onBackpressureBuffer();
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile WebSocketSession client;
    private volatile Disposable upstream;
    private volatile GraphQlStreams.Registration registration;
    private volatile String[] secrets = new String[0];
    private volatile String protocol;
    private volatile CloseStatus upstreamClose;
    private volatile CloseStatus closedWith;

    GraphQlSocket(GatewayPrincipal principal, GatewayExchange exchange) {
        this.principal = principal;
        this.exchange = exchange;
    }

    static GraphQlSocket of(WebSocketSession session) {
        return session.getAttributes().get(ATTRIBUTE) instanceof GraphQlSocket socket ? socket : null;
    }

    GatewayPrincipal principal() {
        return principal;
    }

    GatewayExchange exchange() {
        return exchange;
    }

    Set<String> operations() {
        return operations;
    }

    String protocol() {
        return protocol;
    }

    String[] secrets() {
        return secrets;
    }

    void registration(GraphQlStreams.Registration registration) {
        this.registration = registration;
        if (closed.get()) registration.close();
    }

    /** The container's socket. A revocation that arrived before it did closes it the moment it exists. */
    void attach(WebSocketSession session, String protocol) {
        this.client = session;
        this.protocol = protocol;
        if (closed.get()) closeQuietly(session, closedWith);
    }

    void connected(Disposable upstream, String[] secrets) {
        this.secrets = secrets;
        this.upstream = upstream;
        if (closed.get()) upstream.dispose();
    }

    void upstreamClosed(CloseStatus status) {
        this.upstreamClose = status;
    }

    CloseStatus upstreamClose() {
        return upstreamClose == null ? CloseStatus.NORMAL : upstreamClose;
    }

    /** What the caller sent, on its way to the upstream. Buffered until the upstream socket is open. */
    Flux<String> outbound() {
        return toUpstream.asFlux();
    }

    void forward(String text) {
        if (closed.get()) return;
        // Messages from one socket are delivered one at a time, but a close can race them from another
        // thread; a moment's spinning is the price of never dropping a message on that race.
        toUpstream.emitNext(text, Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(100)));
    }

    /** What the upstream sent, on its way to the caller. */
    void deliver(String text) throws IOException {
        var session = client;
        if (session != null && session.isOpen()) session.sendMessage(new TextMessage(text));
    }

    void close(CloseStatus status) {
        if (!closed.compareAndSet(false, true)) return;
        closedWith = status;
        toUpstream.tryEmitComplete();
        var held = upstream;
        if (held != null) held.dispose();
        var admitted = registration;
        if (admitted != null) admitted.close();
        var session = client;
        if (session != null) closeQuietly(session, status);
    }

    boolean isClosed() {
        return closed.get();
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        if (!session.isOpen()) return;
        try {
            session.close(status == null ? CloseStatus.NORMAL : status);
        } catch (IOException | RuntimeException ex) {
            // Already going; nothing left to tell it.
        }
    }
}
