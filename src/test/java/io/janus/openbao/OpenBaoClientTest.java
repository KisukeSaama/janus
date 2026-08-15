package io.janus.openbao;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;

/**
 * The store the secrets actually live in.
 *
 * <p>Run against a real HTTP server on a loopback port rather than a mocked client, because the
 * client is built inside the constructor and half of what matters here is what goes on the wire:
 * the token header, the mount and path the request is addressed to, and the fact that a refusal is
 * reported without the body that came with it.
 */
class OpenBaoClientTest {
    private HttpServer server;
    private final List<HttpExchange> received = new ArrayList<>();

    /** What the next request will be answered with. */
    private int status = 200;

    private String body = "{}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.add(exchange);
            exchange.getRequestBody().readAllBytes();
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
            try (var out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private OpenBaoClient client() {
        return client("root-token");
    }

    private OpenBaoClient client(String token) {
        return new OpenBaoClient(
                new OpenBaoProperties("http://127.0.0.1:" + server.getAddress().getPort(), token, "secret"));
    }

    private HttpExchange onlyRequest() {
        assertThat(received).hasSize(1);
        return received.getFirst();
    }

    private void willAnswer(int status, String body) {
        this.status = status;
        this.body = body;
    }

    // --- configuration ------------------------------------------------------

    @Test
    void refusesToStartWithoutAnAddress() {
        assertThatThrownBy(() -> new OpenBaoClient(new OpenBaoProperties(null, "token", "secret")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("address");
    }

    /**
     * A deployment with an address but no token would otherwise fail on the first proxied call,
     * with a message about the secret rather than about the configuration.
     */
    @Test
    void refusesToActWithoutAToken() {
        assertThatThrownBy(() -> client("").read("janus/spotify/key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token is not configured");
        assertThat(received).isEmpty();
    }

    /**
     * The vault token and every secret read with it travel over this address. Reached in clear
     * across a network, that is bounded by the host the two containers share and by nothing else the
     * moment they stop sharing one — with no symptom at all, since the deployment goes on working
     * exactly as it would have with TLS. So it is said once, at startup.
     */
    @Test
    void saysSoWhenTheStoreIsReachedInClearAcrossANetwork() {
        var recorded = recording();

        new OpenBaoClient(new OpenBaoProperties("http://openbao:8200", "root-token", "secret"));

        assertThat(warnings(recorded)).singleElement().asString().contains("travel in clear", "http://openbao:8200");
    }

    /** No wire, nothing to say: a developer running both locally needs no certificate to be told about. */
    @Test
    void saysNothingForLoopbackOrForTls() {
        var recorded = recording();

        client();
        new OpenBaoClient(new OpenBaoProperties("http://localhost:8200", "root-token", "secret"));
        new OpenBaoClient(new OpenBaoProperties("https://vault.example.test", "root-token", "secret"));

        assertThat(warnings(recorded)).isEmpty();
    }

    /** Collects what this class logs, for the two tests above. Detached when the test ends. */
    private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> recording() {
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        attached = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(OpenBaoClient.class);
        attached.addAppender(appender);
        return appender;
    }

    private ch.qos.logback.classic.Logger attached;

    @AfterEach
    void detachRecording() {
        if (attached != null) attached.detachAndStopAllAppenders();
        attached = null;
    }

    private static List<String> warnings(
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> recorded) {
        return recorded.list.stream()
                .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .toList();
    }

    // --- reading ------------------------------------------------------------

    @Test
    void readsTheValueOutOfAKvVersionTwoResponse() {
        willAnswer(200, "{\"data\":{\"data\":{\"value\":\"sk_live_31337\"},\"metadata\":{\"version\":1}}}");

        assertThat(client().read("janus/spotify/key")).isEqualTo("sk_live_31337");
    }

    @Test
    void addressesTheMountAndPathItWasGiven() {
        willAnswer(200, "{\"data\":{\"data\":{\"value\":\"v\"}}}");

        client().read("janus/spotify/key");

        assertThat(onlyRequest().getRequestURI().getPath()).isEqualTo("/v1/secret/data/janus/spotify/key");
        assertThat(onlyRequest().getRequestMethod()).isEqualTo("GET");
    }

    @Test
    void presentsTheTokenOnEveryCall() {
        willAnswer(200, "{\"data\":{\"data\":{\"value\":\"v\"}}}");

        client().read("janus/spotify/key");

        assertThat(onlyRequest().getRequestHeaders().getFirst("X-Vault-Token")).isEqualTo("root-token");
        assertThat(onlyRequest().getRequestHeaders().getFirst("X-Vault-Request"))
                .isEqualTo("true");
    }

    /** An empty secret is a missing secret; sending it upstream would look like an anonymous call. */
    @Test
    void treatsAnEmptyOrAbsentValueAsMissing() {
        willAnswer(200, "{\"data\":{\"data\":{\"value\":\"   \"}}}");
        assertThatThrownBy(() -> client().read("janus/spotify/key")).hasMessageContaining("missing");

        received.clear();
        willAnswer(200, "{\"data\":{\"data\":{}}}");
        assertThatThrownBy(() -> client().read("janus/spotify/key")).hasMessageContaining("missing");

        received.clear();
        willAnswer(200, "{}");
        assertThatThrownBy(() -> client().read("janus/spotify/key")).hasMessageContaining("missing");
    }

    /** A value that is not a string is not a secret either, and must not be coerced into one. */
    @Test
    void treatsANonStringValueAsMissing() {
        willAnswer(200, "{\"data\":{\"data\":{\"value\":12345}}}");

        assertThatThrownBy(() -> client().read("janus/spotify/key")).hasMessageContaining("missing");
    }

    /** OpenBao's own error bodies quote the request; only the status is ever surfaced. */
    @Test
    void reportsARefusalByStatusWithoutTheBodyThatCameWithIt() {
        willAnswer(403, "{\"errors\":[\"permission denied on secret/data/janus/spotify/key\"]}");

        assertThatThrownBy(() -> client().read("janus/spotify/key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("status 403")
                .hasMessageNotContaining("permission denied");
    }

    @Test
    void reportsAnUnreachableStoreAsSuch() {
        server.stop(0);

        assertThatThrownBy(() -> client().read("janus/spotify/key")).hasMessage("OpenBao is unreachable");
    }

    // --- writing and deleting -----------------------------------------------

    @Test
    void wrapsTheSecretTheWayKvVersionTwoExpects() throws IOException {
        willAnswer(200, "{}");
        var body = new StringBuilder();
        server.removeContext("/");
        server.createContext("/", exchange -> {
            received.add(exchange);
            body.append(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        client().write("janus/spotify/key", "sk_live_31337");

        assertThat(onlyRequest().getRequestMethod()).isEqualTo("POST");
        assertThat(onlyRequest().getRequestURI().getPath()).isEqualTo("/v1/secret/data/janus/spotify/key");
        assertThat(body.toString()).isEqualTo("{\"data\":{\"value\":\"sk_live_31337\"}}");
    }

    /** Deleting the metadata is what removes every version; deleting the data leaves them behind. */
    @Test
    void deletesEveryVersionOfTheSecretRatherThanTheLatestOne() {
        willAnswer(204, "");

        client().delete("janus/spotify/key");

        assertThat(onlyRequest().getRequestMethod()).isEqualTo("DELETE");
        assertThat(onlyRequest().getRequestURI().getPath()).isEqualTo("/v1/secret/metadata/janus/spotify/key");
    }

    @Test
    void reportsARefusedWriteByStatusWithoutItsBody() {
        willAnswer(500, "{\"errors\":[\"storage backend is sealed at /var/lib/openbao\"]}");

        assertThatThrownBy(() -> client().write("janus/spotify/key", "value"))
                .hasMessageContaining("store")
                .hasMessageContaining("status 500")
                .hasMessageNotContaining("/var/lib/openbao");
    }

    // --- what may be addressed ----------------------------------------------

    /**
     * The path is interpolated into a URL, so a traversal in it would address somebody else's
     * secret. It is refused before a request is built rather than escaped afterwards.
     */
    @Test
    void refusesAPathThatCouldAddressSomethingElse() {
        for (String path : List.of("janus/../root/key", "janus//key", "janus/key?list=true", "janus/key#top", "")) {
            assertThatThrownBy(() -> client().read(path))
                    .describedAs("path %s", path)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid secret path");
        }
        assertThat(received).isEmpty();
    }

    @Test
    void refusesANullPath() {
        assertThatThrownBy(() -> client().read(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
