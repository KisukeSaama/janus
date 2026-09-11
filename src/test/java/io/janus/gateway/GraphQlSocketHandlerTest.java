package io.janus.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.time.Duration;
import java.util.*;

import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import io.janus.audit.AuditOutcome;
import io.janus.audit.AuditService;
import io.janus.credentials.Identity;
import io.janus.gateway.graphql.*;
import io.janus.grants.Grant;
import io.janus.grants.GrantScope;
import io.janus.providers.Provider;
import io.janus.security.GatewayPrincipal;
import io.janus.shared.ErrorCode;
import io.janus.testing.Fixtures;

/**
 * A relayed GraphQL WebSocket, one message at a time. The upstream socket is stood in for; what is
 * under test is what the caller's messages are allowed to become, and how the socket ends.
 */
class GraphQlSocketHandlerTest {
    private static final String SECRET = "sk_live_31337";

    private final GatewayTrafficService traffic = mock(GatewayTrafficService.class);
    private final AuditService audit = mock(AuditService.class);
    private final GatewayMetrics metrics = mock(GatewayMetrics.class);
    private final WebSocketClient client = mock(WebSocketClient.class);
    private final GraphQlProperties properties = new GraphQlProperties(300, 0, 20, 2, 100, 25);
    private final ObjectMapper mapper = new ObjectMapper();
    private final GraphQlSocketHandler handler = new GraphQlSocketHandler(
            new GraphQlInspector(mapper, new PersistedQueries(properties), properties),
            traffic,
            properties,
            audit,
            metrics,
            mapper,
            client,
            client,
            1_000_000);

    private final io.janus.accounts.Account owner = Fixtures.owner();
    private final Provider provider = Fixtures.provider(owner, "github");
    private final io.janus.applications.Application application = Fixtures.application(owner);
    private final Grant grant = Fixtures.grant(application, provider, Fixtures.credential(provider));
    private final WebSocketSession session = mock(WebSocketSession.class);
    private final Map<String, Object> attributes = new HashMap<>();
    private GraphQlSocket socket;

    @BeforeEach
    void setUp() throws Exception {
        provider.applyGraphQl(new Provider.GraphQl("/graphql", 0, 0));
        var route = GatewayPath.parse("/gateway/github/graphql", "github", null);
        var exchange = new GatewayExchange(
                provider,
                grant,
                application.getId(),
                HttpMethod.GET,
                route,
                new HttpHeaders(),
                null,
                "corr",
                null,
                null);
        socket = new GraphQlSocket(
                new GatewayPrincipal(application.getId(), "checkout", owner.getId(), Set.of()), exchange);
        attributes.put(GraphQlSocket.ATTRIBUTE, socket);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        when(session.getAcceptedProtocol()).thenReturn(GraphQlSocketHandler.TRANSPORT_WS);
        when(traffic.openSocket(any()))
                .thenReturn(new GatewayTrafficService.SocketTarget(
                        URI.create("wss://api.example.com/graphql"),
                        new HttpHeaders(),
                        new String[] {SECRET},
                        Identity.APP));
        when(client.execute(any(URI.class), any(HttpHeaders.class), any())).thenReturn(Mono.never());
    }

    private void open() {
        handler.afterConnectionEstablished(session);
    }

    private void receive(String text) throws Exception {
        handler.handleMessage(session, new TextMessage(text));
    }

    private int closedWith() throws Exception {
        var captor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(captor.capture());
        return captor.getValue().getCode();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<String> toCaller() throws Exception {
        ArgumentCaptor<WebSocketMessage> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());
        return captor.getAllValues().stream()
                .map(message -> (String) message.getPayload())
                .toList();
    }

    private String upstreamReceived() {
        return socket.outbound().take(1).blockFirst(Duration.ofSeconds(1));
    }

    private static String subscribe(String id, String query) {
        return "{\"id\":\"" + id + "\",\"type\":\"subscribe\",\"payload\":{\"query\":\"" + query + "\"}}";
    }

    // --- opening ----------------------------------------------------------------

    @Test
    void opensTheUpstreamSocketAndJournalsIt() {
        open();

        verify(client).execute(eq(URI.create("wss://api.example.com/graphql")), any(HttpHeaders.class), any());
        verify(audit).recordGateway(argThat(event -> event.status() == 101 && event.outcome() == AuditOutcome.SUCCESS));
    }

    @Test
    void closesASocketNothingAdmitted() throws Exception {
        attributes.clear();

        open();

        assertThat(closedWith()).isEqualTo(CloseStatus.SERVER_ERROR.getCode());
    }

    @Test
    void closesASocketSpeakingAnotherProtocol() throws Exception {
        when(session.getAcceptedProtocol()).thenReturn("chat");

        open();

        assertThat(closedWith()).isEqualTo(4406);
        verify(traffic, never()).openSocket(any());
    }

    @Test
    void asksTheCallerToComeBackLaterWhenAnAllowanceIsSpent() throws Exception {
        when(traffic.openSocket(any()))
                .thenThrow(new Throttled(ErrorCode.RATE_LIMIT_GRANT, "spent", 1, new HttpHeaders()));

        open();

        assertThat(closedWith()).isEqualTo(1013);
    }

    @Test
    void closesWithTheReasonWhenTheDestinationCannotBeReachedThisWay() throws Exception {
        when(traffic.openSocket(any()))
                .thenThrow(new GatewayController.Denied(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "signs"));

        open();

        assertThat(closedWith()).isEqualTo(4400);
    }

    @Test
    void closesWhenTheUpstreamCannotBePrepared() throws Exception {
        when(traffic.openSocket(any())).thenThrow(new IllegalStateException("boom"));

        open();

        assertThat(closedWith()).isEqualTo(1011);
    }

    // --- what the caller sends ----------------------------------------------------

    @Test
    void relaysTheProtocolsOwnMessagesAsTheyCame() throws Exception {
        open();

        receive("{\"type\":\"connection_init\",\"payload\":{}}");

        assertThat(upstreamReceived()).contains("connection_init");
    }

    @Test
    void forwardsAnAdmittedOperationAndCountsIt() throws Exception {
        grant.applyScope(GrantScope.of(null, null, true, "QUERY,SUBSCRIPTION", null));
        open();

        receive(subscribe("1", "subscription { issues { id } }"));

        assertThat(upstreamReceived()).contains("\"id\":\"1\"");
        assertThat(socket.operations()).containsExactly("1");
        verify(traffic).admitOperation(any());
    }

    /** Refused with the protocol's own error, and the code a request would have carried. */
    @Test
    void refusesAnOperationTheGrantDoesNotAdmitAndKeepsTheSocketOpen() throws Exception {
        grant.applyScope(GrantScope.of(null, null, true, "QUERY", null));
        open();

        receive(subscribe("1", "mutation { wipe }"));

        assertThat(toCaller()).singleElement().satisfies(frame -> {
            assertThat(frame).contains("\"type\":\"error\"").contains("graphql_operation_not_granted");
            assertThat(frame).contains("\"payload\":[");
        });
        assertThat(socket.operations()).isEmpty();
        verify(session, never()).close(any());
    }

    /** A socket must not be the way around the quota a request is held to. */
    @Test
    void holdsEveryOperationToTheApplicationsAllowance() throws Exception {
        doThrow(new Throttled(ErrorCode.RATE_LIMIT_GRANT, "spent", 1, new HttpHeaders()))
                .when(traffic)
                .admitOperation(any());
        open();

        receive(subscribe("1", "{ viewer }"));

        assertThat(toCaller()).singleElement().asString().contains("rate_limit_grant");
    }

    @Test
    void refusesAnOperationBeyondWhatOneSocketMayCarry() throws Exception {
        open();
        receive(subscribe("1", "{ a }"));
        receive(subscribe("2", "{ b }"));

        receive(subscribe("3", "{ c }"));

        assertThat(toCaller()).singleElement().asString().contains("stream_limit");
    }

    @Test
    void closesOnAnOperationIdAlreadyInUse() throws Exception {
        open();
        receive(subscribe("1", "{ a }"));

        receive(subscribe("1", "{ b }"));

        assertThat(closedWith()).isEqualTo(4409);
    }

    @Test
    void forgetsAnOperationTheCallerCompleted() throws Exception {
        open();
        receive(subscribe("1", "{ a }"));

        receive("{\"id\":\"1\",\"type\":\"complete\"}");

        assertThat(socket.operations()).isEmpty();
    }

    @Test
    void asksForTheDocumentBehindAnUnknownPersistedQuery() throws Exception {
        grant.applyScope(GrantScope.of(null, null, true, "QUERY", null));
        open();

        receive("{\"id\":\"1\",\"type\":\"subscribe\",\"payload\":{\"extensions\":"
                + "{\"persistedQuery\":{\"version\":1,\"sha256Hash\":\"abc\"}}}}");

        assertThat(toCaller()).singleElement().asString().contains("PERSISTED_QUERY_NOT_FOUND");
    }

    @Test
    void closesOnAMessageThatIsNotJson() throws Exception {
        open();

        receive("not json");

        assertThat(closedWith()).isEqualTo(4400);
    }

    @Test
    void closesOnAMessageWithoutAType() throws Exception {
        open();

        receive("{\"id\":\"1\"}");

        assertThat(closedWith()).isEqualTo(4400);
    }

    @Test
    void closesOnAMessageTheProtocolDoesNotDefine() throws Exception {
        open();

        receive("{\"type\":\"start\",\"id\":\"1\"}");

        assertThat(closedWith()).isEqualTo(4400);
    }

    @Test
    void closesOnAnOperationWithoutAnId() throws Exception {
        open();

        receive("{\"type\":\"subscribe\",\"payload\":{\"query\":\"{ a }\"}}");

        assertThat(closedWith()).isEqualTo(4400);
    }

    // --- the older protocol -------------------------------------------------------

    @Test
    void speaksTheOlderProtocolInItsOwnTerms() throws Exception {
        when(session.getAcceptedProtocol()).thenReturn(GraphQlSocketHandler.LEGACY_WS);
        grant.applyScope(GrantScope.of(null, null, true, "QUERY", null));
        open();

        receive("{\"id\":\"1\",\"type\":\"start\",\"payload\":{\"query\":\"mutation { wipe }\"}}");
        receive("{\"id\":\"2\",\"type\":\"start\",\"payload\":{\"query\":\"{ viewer }\"}}");
        receive("{\"id\":\"2\",\"type\":\"stop\"}");

        assertThat(toCaller()).singleElement().asString().contains("\"payload\":{");
        assertThat(socket.operations()).isEmpty();
    }

    // --- what the upstream sends --------------------------------------------------

    @Test
    void scrubsTheCredentialFromEveryFrameComingBack() throws Exception {
        open();

        handler.fromUpstream(socket, "{\"type\":\"next\",\"id\":\"1\",\"payload\":\"" + SECRET + "\"}");

        assertThat(toCaller())
                .singleElement()
                .asString()
                .contains(SecretRedactor.PLACEHOLDER)
                .doesNotContain(SECRET);
    }

    @Test
    void forgetsAnOperationTheUpstreamEnded() throws Exception {
        open();
        receive(subscribe("1", "{ a }"));

        handler.fromUpstream(socket, "{\"type\":\"complete\",\"id\":\"1\"}");

        assertThat(socket.operations()).isEmpty();
    }

    // --- ending -------------------------------------------------------------------

    @Test
    void endsTheRelayWhenEitherSideGoes() {
        open();

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(socket.isClosed()).isTrue();
    }

    @Test
    void endsTheRelayOnATransportFailure() {
        open();

        handler.handleTransportError(session, new IllegalStateException("reset"));

        assertThat(socket.isClosed()).isTrue();
    }
}
