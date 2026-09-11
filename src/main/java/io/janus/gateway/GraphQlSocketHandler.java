package io.janus.gateway;

import java.io.IOException;
import java.time.Duration;
import java.util.*;

import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.janus.audit.AuditOutcome;
import io.janus.audit.AuditService;
import io.janus.gateway.graphql.*;
import io.janus.shared.ErrorCode;

/**
 * GraphQL over WebSocket, relayed one operation at a time.
 *
 * <p>Both protocols in use are spoken: {@code graphql-transport-ws}, which is the current one, and
 * {@code graphql-ws}, the Apollo protocol it replaced and that a great many servers still offer. The
 * caller chooses in its handshake, and the upstream is asked for the same one, so Janus never
 * translates between them. It only reads.
 *
 * <p>What it reads is every operation the caller starts. A socket is authorised once, at the
 * handshake; each {@code subscribe} (or {@code start}) is then read, checked against the grant, the
 * destination's limits and the application's allowance exactly as the same operation sent as a request
 * would be, and forwarded only if it passes. A refused one is answered with the protocol's own
 * {@code error} message, carrying the same code a request would have been refused with, and the socket
 * stays open for the operations that are admitted. Everything else ({@code connection_init}, pings,
 * completions) is relayed as it came.
 *
 * <p>Every frame coming back is scrubbed of the credential, like every response.
 */
@Component
public class GraphQlSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(GraphQlSocketHandler.class);

    static final String TRANSPORT_WS = "graphql-transport-ws";
    static final String LEGACY_WS = "graphql-ws";
    static final List<String> PROTOCOLS = List.of(TRANSPORT_WS, LEGACY_WS);

    /** Relayed as they come, per protocol. Anything not listed, and not an operation, closes the socket. */
    private static final Set<String> TRANSPORT_RELAYED = Set.of("connection_init", "ping", "pong");

    private static final Set<String> LEGACY_RELAYED = Set.of("connection_init", "connection_terminate");

    /** The close codes graphql-transport-ws defines, used for the same reasons by both protocols. */
    static final CloseStatus BAD_MESSAGE = new CloseStatus(4400, "Invalid message");

    static final CloseStatus REVOKED = new CloseStatus(4403, "Access withdrawn");
    static final CloseStatus UNACCEPTABLE = new CloseStatus(4406, "Subprotocol not acceptable");
    static final CloseStatus UPSTREAM_FAILED = new CloseStatus(1011, "Upstream connection failed");
    static final CloseStatus TRY_LATER = new CloseStatus(1013, "Try again later");

    private static final int SEND_TIME_LIMIT_MILLIS = 10_000;
    private static final int MAX_OPERATION_ID = 128;

    private final GraphQlInspector inspector;
    private final GatewayTrafficService traffic;
    private final GraphQlProperties properties;
    private final AuditService audit;
    private final GatewayMetrics metrics;
    private final ObjectMapper mapper;
    private final WebSocketClient publicClient;
    private final WebSocketClient privateClient;
    private final int messageLimit;

    public GraphQlSocketHandler(
            GraphQlInspector inspector,
            GatewayTrafficService traffic,
            GraphQlProperties properties,
            AuditService audit,
            GatewayMetrics metrics,
            ObjectMapper mapper,
            WebSocketClient gatewayWebSocketClient,
            WebSocketClient gatewayPrivateWebSocketClient,
            @Value("${janus.gateway.max-request-bytes:10485760}") int maxRequestBytes) {
        this.inspector = inspector;
        this.traffic = traffic;
        this.properties = properties;
        this.audit = audit;
        this.metrics = metrics;
        this.mapper = mapper;
        this.publicClient = gatewayWebSocketClient;
        this.privateClient = gatewayPrivateWebSocketClient;
        // A message is an operation, not an upload; the request limit is the ceiling, and a megabyte is
        // more than any document a person or a generator writes.
        this.messageLimit = Math.max(1, Math.min(maxRequestBytes, StreamRelay.MAX_LINE_BYTES));
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        var socket = GraphQlSocket.of(session);
        if (socket == null) {
            closeQuietly(session, CloseStatus.SERVER_ERROR);
            return;
        }
        session.setTextMessageSizeLimit(messageLimit);
        String protocol = session.getAcceptedProtocol();
        socket.attach(new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MILLIS, messageLimit), protocol);
        if (protocol == null || !PROTOCOLS.contains(protocol)) {
            socket.close(UNACCEPTABLE);
            return;
        }

        GatewayTrafficService.SocketTarget target;
        try {
            target = traffic.openSocket(socket.exchange());
        } catch (Throttled throttled) {
            record(socket, AuditOutcome.THROTTLED, 429, "websocket refused, " + throttled.getMessage(), null);
            socket.close(TRY_LATER);
            return;
        } catch (GatewayController.Denied denied) {
            record(
                    socket,
                    AuditOutcome.DENIED,
                    denied.status.value(),
                    "websocket refused, " + denied.getMessage(),
                    null);
            socket.close(new CloseStatus(4400, truncate(denied.getMessage())));
            return;
        } catch (RuntimeException ex) {
            var failure = UpstreamFailure.of(ex);
            log.warn(
                    "Could not prepare an upstream WebSocket [correlationId={}]",
                    socket.exchange().correlationId(),
                    ex);
            record(socket, AuditOutcome.ERROR, failure.status().value(), failure.detail(), null);
            socket.close(UPSTREAM_FAILED);
            return;
        }

        record(socket, AuditOutcome.SUCCESS, 101, "websocket opened, " + protocol, null);
        var client = socket.exchange().provider().isAllowPrivateDestination() ? privateClient : publicClient;
        var connection = client.execute(target.uri(), target.headers(), new Upstream(socket, protocol))
                .subscribe(
                        done -> {},
                        failure -> {
                            log.warn(
                                    "Upstream WebSocket ended with a failure [correlationId={}]: {}",
                                    socket.exchange().correlationId(),
                                    failure.toString());
                            socket.close(UPSTREAM_FAILED);
                        },
                        () -> socket.close(socket.upstreamClose()));
        socket.connected(connection, target.secrets());
    }

    /**
     * The upstream half. Asks for the protocol the caller chose, relays what the caller sends, and
     * delivers what comes back. The socket lasts until either side closes, the upstream goes quiet for
     * longer than a stream may, or the deployment's ceiling on a stream's life is reached.
     */
    private final class Upstream implements org.springframework.web.reactive.socket.WebSocketHandler {
        private final GraphQlSocket socket;
        private final String protocol;

        Upstream(GraphQlSocket socket, String protocol) {
            this.socket = socket;
            this.protocol = protocol;
        }

        @Override
        public List<String> getSubProtocols() {
            return List.of(protocol);
        }

        @Override
        public Mono<Void> handle(org.springframework.web.reactive.socket.WebSocketSession session) {
            session.closeStatus()
                    .subscribe(status -> socket.upstreamClosed(relayable(status.getCode(), status.getReason())));
            Flux<String> inbound = session.receive().map(WebSocketMessage::getPayloadAsText);
            if (properties.streamIdleTimeoutSeconds() > 0)
                inbound = inbound.timeout(Duration.ofSeconds(properties.streamIdleTimeoutSeconds()));
            Mono<Void> in = inbound.doOnNext(text -> fromUpstream(socket, text)).then();
            Mono<Void> out = session.send(socket.outbound().map(session::textMessage));
            Mono<Void> lifetime = properties.maxStreamSeconds() > 0
                    ? Mono.delay(Duration.ofSeconds(properties.maxStreamSeconds()))
                            .then()
                    : Mono.never();
            return Mono.firstWithSignal(in, out, lifetime).then(session.close());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        var socket = GraphQlSocket.of(session);
        if (socket == null || socket.isClosed()) return;
        String text = message.getPayload();
        JsonNode node;
        try {
            node = mapper.readTree(text);
        } catch (JacksonException ex) {
            socket.close(BAD_MESSAGE);
            return;
        }
        var typeNode = node.path("type");
        if (!node.isObject() || !typeNode.isString()) {
            socket.close(BAD_MESSAGE);
            return;
        }
        String type = typeNode.stringValue();
        boolean transport = TRANSPORT_WS.equals(socket.protocol());
        String starts = transport ? "subscribe" : "start";
        String stops = transport ? "complete" : "stop";

        if (type.equals(starts)) start(socket, node, text, transport);
        else if (type.equals(stops)) {
            var id = node.path("id");
            if (id.isString()) socket.operations().remove(id.stringValue());
            socket.forward(text);
        } else if ((transport ? TRANSPORT_RELAYED : LEGACY_RELAYED).contains(type)) socket.forward(text);
        else socket.close(BAD_MESSAGE);
    }

    /** One operation, read and decided before it is forwarded. */
    private void start(GraphQlSocket socket, JsonNode node, String text, boolean transport) {
        var idNode = node.path("id");
        if (!idNode.isString()
                || idNode.stringValue().isEmpty()
                || idNode.stringValue().length() > MAX_OPERATION_ID) {
            socket.close(BAD_MESSAGE);
            return;
        }
        String id = idNode.stringValue();
        if (socket.operations().contains(id)) {
            socket.close(new CloseStatus(4409, "Subscriber for " + id + " already exists"));
            return;
        }
        if (socket.operations().size() >= properties.maxOperationsPerSocket()) {
            String detail = "This socket already carries " + properties.maxOperationsPerSocket() + " operations";
            refuse(socket, id, transport, ErrorCode.STREAM_LIMIT, detail);
            record(socket, AuditOutcome.THROTTLED, 429, "websocket operation refused, " + detail, null);
            return;
        }

        var exchange = socket.exchange();
        var provider = exchange.provider();
        var scope = exchange.grant().getScope();
        GraphQlCall call = null;
        try {
            var inspection =
                    inspector.inspectPayload(provider, node.path("payload"), GraphQlScope.enforcing(provider, scope));
            if (inspection instanceof GraphQlInspector.UnknownPersistedQuery) {
                send(socket, error(id, transport, "PersistedQueryNotFound", "PERSISTED_QUERY_NOT_FOUND", exchange));
                record(
                        socket,
                        AuditOutcome.SUCCESS,
                        200,
                        "graphql, persisted query unknown to Janus, document requested",
                        null);
                return;
            }
            call = ((GraphQlInspector.Read) inspection).call();
            GraphQlScope.check(scope, call);
            traffic.admitOperation(exchange);
        } catch (GraphQlRefusal refusal) {
            refuse(socket, id, transport, refusal.code(), refusal.getMessage());
            record(
                    socket,
                    AuditOutcome.DENIED,
                    refusal.status().value(),
                    "websocket operation refused, " + refusal.getMessage(),
                    call);
            return;
        } catch (Throttled throttled) {
            refuse(socket, id, transport, throttled.code, throttled.getMessage());
            record(socket, AuditOutcome.THROTTLED, 429, "websocket operation refused, " + throttled.getMessage(), call);
            return;
        }
        socket.operations().add(id);
        socket.forward(text);
        record(socket, AuditOutcome.SUCCESS, 200, "websocket operation", call);
    }

    /**
     * A frame from the upstream, scrubbed and delivered. Completions and errors are also read, so the
     * count of operations in flight goes down when the upstream ends one rather than only when the
     * caller does.
     */
    void fromUpstream(GraphQlSocket socket, String text) {
        if (text.contains("\"complete\"") || text.contains("\"error\"")) {
            try {
                var node = mapper.readTree(text);
                var type = node.path("type");
                var id = node.path("id");
                if (type.isString()
                        && id.isString()
                        && (type.stringValue().equals("complete")
                                || type.stringValue().equals("error")))
                    socket.operations().remove(id.stringValue());
            } catch (JacksonException ex) {
                // Not ours to judge; relayed as it came.
            }
        }
        try {
            socket.deliver(SecretRedactor.scrubText(text, socket.secrets()));
        } catch (IOException | RuntimeException ex) {
            socket.close(CloseStatus.SESSION_NOT_RELIABLE);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        var socket = GraphQlSocket.of(session);
        if (socket != null) socket.close(status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        var socket = GraphQlSocket.of(session);
        if (socket != null) socket.close(CloseStatus.SERVER_ERROR);
    }

    /** The protocol's own refusal, carrying the code a request would have been refused with. */
    private void refuse(GraphQlSocket socket, String id, boolean transport, ErrorCode code, String detail) {
        send(socket, error(id, transport, detail, code.wire(), socket.exchange()));
    }

    /**
     * {@code graphql-transport-ws} carries a list of GraphQL errors in an {@code error} message; the
     * older protocol carries one. Both put the code where every GraphQL client looks for one.
     */
    private String error(String id, boolean transport, String message, String code, GatewayExchange exchange) {
        var extensions = Map.of("code", code, "correlationId", exchange.correlationId());
        var error = Map.of("message", message, "extensions", extensions);
        var frame = new LinkedHashMap<String, Object>();
        frame.put("id", id);
        frame.put("type", "error");
        frame.put("payload", transport ? List.of(error) : error);
        return mapper.writeValueAsString(frame);
    }

    private void send(GraphQlSocket socket, String frame) {
        try {
            socket.deliver(frame);
        } catch (IOException | RuntimeException ex) {
            socket.close(CloseStatus.SESSION_NOT_RELIABLE);
        }
    }

    private void record(GraphQlSocket socket, AuditOutcome outcome, int status, String detail, GraphQlCall call) {
        var exchange = socket.exchange();
        String described = call == null ? detail : call.describe() + ", " + detail;
        audit.recordGateway(new AuditService.GatewayEvent(
                exchange.applicationId(),
                socket.principal().ownerId(),
                outcome,
                exchange.provider().getId(),
                "WS",
                exchange.route().decodedPath(),
                status,
                described,
                exchange.correlationId()));
        metrics.record(
                exchange.provider().getSlug(),
                outcome,
                CacheStatus.BYPASS,
                status,
                0,
                call == null ? null : call.governingType());
    }

    /**
     * The upstream's close code, if a caller may be handed it. The codes a peer may never send
     * (1005, 1006, 1015) mean the connection failed rather than that anybody closed it.
     */
    static CloseStatus relayable(int code, String reason) {
        boolean sendable =
                (code >= 1000 && code <= 1003) || (code >= 1007 && code <= 1014) || (code >= 3000 && code <= 4999);
        return sendable ? new CloseStatus(code, reason == null ? "" : truncate(reason)) : UPSTREAM_FAILED;
    }

    /** A close reason is limited to 123 bytes on the wire. */
    private static String truncate(String reason) {
        return reason.length() <= 120 ? reason : reason.substring(0, 120);
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException ex) {
            // Already gone.
        }
    }
}
