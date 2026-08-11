package io.janus;

import java.time.Duration;

import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.netty.http.client.HttpClient;

/**
 * A running Janus, on a real PostgreSQL, answering on a real port.
 *
 * <p>What the unit tests cannot answer lives here: whether the migrations apply in order, whether
 * the schema they produce is the one the entities are mapped to, and whether the security chain
 * refuses what it is supposed to refuse. None of that is decidable from a mock, and all of it fails
 * at startup in production rather than in a review.
 *
 * <p>A real port rather than a mocked dispatcher, deliberately. Half of what is asserted below —
 * the filter order, the session cookie, CORS, the throttles — lives in the servlet chain, and a
 * mocked dispatcher is precisely the thing that does not run it.
 *
 * <p>The container is started once for the whole suite and shared; Spring's context caching then
 * keeps one application up across every subclass, so the price is paid once rather than per class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIf(value = "io.janus.DockerAvailable#yes", disabledReason = "Docker is not available on this machine")
@Testcontainers(disabledWithoutDocker = true)
public abstract class IntegrationTest {

    /**
     * One container for the whole suite, started here and never stopped.
     *
     * <p>Deliberately not a {@code @Container}: that hands its lifecycle to the extension, which
     * stops it after each class. Spring's context cache keeps the application — and its connection
     * pool — alive across classes, so the second class would run against a database that is no
     * longer there, and fail as timeouts, 401s and a red health probe rather than as anything
     * resembling the real cause.
     *
     * <p>Guarded rather than unconditional, so a machine without Docker skips these tests instead of
     * failing to initialise this class.
     */
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        if (DockerAvailable.yes()) POSTGRES.start();
    }

    @LocalServerPort
    private int port;

    /**
     * OpenBao is not started for these tests. Every path that would read a secret is covered by the
     * unit tests against a real HTTP server; what is asked here is whether the application comes up
     * and enforces its rules, and an address that refuses connections is enough for that.
     */
    @DynamicPropertySource
    static void janusProperties(DynamicPropertyRegistry registry) {
        registry.add("janus.openbao.address", () -> "http://127.0.0.1:1");
        registry.add("janus.openbao.token", () -> "test-token");
        registry.add("janus.admin.password", () -> ADMIN_PASSWORD);
        registry.add("janus.admin.email", () -> "kisuke@example.com");
    }

    protected static final String ADMIN_USERNAME = "kisuke";
    protected static final String ADMIN_PASSWORD = "7Qb!vTz2LmXe4RpA9dWf";

    /**
     * A client pointed at this instance, keeping no cookies of its own.
     *
     * <p>On a fresh connection every time, deliberately. A pooled keep-alive socket that the server
     * has since closed fails as "connection prematurely closed" on whichever test happens to pick it
     * up next — a flake that says nothing about the code and moves between runs.
     */
    protected WebTestClient http() {
        return WebTestClient.bindToServer(new ReactorClientHttpConnector(HttpClient.newConnection()))
                .baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(20))
                .build();
    }
}
