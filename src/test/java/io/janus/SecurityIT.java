package io.janus;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * The security chain, as a caller actually meets it.
 *
 * <p>Every rule here is one that only exists once the filters are assembled in order: which door
 * accepts which credential, what a missing one is answered with, and — the load-bearing one — that
 * the two doors do not open each other. None of it is decidable from a unit test of any one filter.
 */
class SecurityIT extends IntegrationTest {

    // --- the administration door ---------------------------------------------

    @Test
    void refusesAnAdministrationCallCarryingNoCredentials() {
        http().get()
                .uri("/api/admin/providers")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void acceptsTheAccountTheDeploymentWasBootstrappedWith() {
        http().get()
                .uri("/api/admin/providers")
                .headers(headers -> headers.setBasicAuth(ADMIN_USERNAME, ADMIN_PASSWORD))
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void refusesAWrongPasswordTheSameWayItRefusesNoPassword() {
        http().get()
                .uri("/api/admin/providers")
                .headers(headers -> headers.setBasicAuth(ADMIN_USERNAME, "not-the-password"))
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    /** Asking "who am I" has to be possible before the answer is "nobody". */
    @Test
    void letsTheConsoleAskWhoIsSignedInWithoutBeingSignedIn() {
        http().get().uri("/api/admin/session").exchange().expectStatus().isUnauthorized();
    }

    /**
     * Reachable without credentials, which is what an orchestrator needs. Whether it reports the
     * deployment as healthy is a different question and not this one's: OpenBao is deliberately
     * unreachable here, so the probe answers honestly rather than 200.
     */
    @Test
    void answersAHealthProbeWithoutCredentials() {
        http().get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus()
                .value(status -> org.assertj.core.api.Assertions.assertThat(status)
                        .describedAs("an orchestrator probe is never asked to authenticate")
                        .isNotIn(401, 403));
    }

    /** Metrics describe every owner's traffic at once, so they stay with the deployment's own account. */
    @Test
    void keepsProcessWideMetricsForTheAccountThatAnswersForTheDeployment() {
        http().get()
                .uri("/actuator/metrics")
                .headers(headers -> headers.setBasicAuth(ADMIN_USERNAME, ADMIN_PASSWORD))
                .exchange()
                .expectStatus()
                .isOk();

        http().get().uri("/actuator/metrics").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void refusesAnythingOutsideTheSurfacesItPublishes() {
        http().get()
                .uri("/internal/whatever")
                .headers(headers -> headers.setBasicAuth(ADMIN_USERNAME, ADMIN_PASSWORD))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    // --- the gateway door -----------------------------------------------------

    @Test
    void refusesAProxiedCallCarryingNoApplicationKey() {
        http().get().uri("/gateway/spotify/v1/tracks").exchange().expectStatus().isUnauthorized();
    }

    /**
     * The one that matters most. The console signs people in with a cookie, and without a null
     * security context on this chain a browser holding one would satisfy {@code authenticated()}
     * here — an administrator's open tab could call every proxied API in the deployment.
     */
    @Test
    void doesNotLetAnAdministratorsCredentialsOpenTheGateway() {
        http().get()
                .uri("/gateway/spotify/v1/tracks")
                .headers(headers -> headers.setBasicAuth(ADMIN_USERNAME, ADMIN_PASSWORD))
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    /**
     * A real preflight is answered earlier by the CORS filter. What is refused here is a bare
     * OPTIONS, which Spring MVC would otherwise answer with a route's allowed methods without ever
     * consulting a grant.
     */
    @Test
    void refusesABareOptionsOnTheGateway() {
        // Refused, and answered 401 rather than 403 because the caller is anonymous: there is no
        // identity for a permission to have been denied to.
        http().options()
                .uri("/gateway/spotify/v1/tracks")
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    /** No token exists yet when the exchange is called, so the endpoint authenticates the client itself. */
    @Test
    void letsTheTokenEndpointBeReachedWithoutCredentials() {
        http().post()
                .uri("/oauth/token")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .bodyValue("grant_type=client_credentials")
                .exchange()
                .expectStatus()
                .value(status -> org.assertj.core.api.Assertions.assertThat(status)
                        .describedAs("reached the endpoint rather than the security chain")
                        .isNotEqualTo(403));
    }

    // --- what every answer carries -------------------------------------------

    @Test
    void statesItsFramingAndReferrerRulesOnEveryAdministrationAnswer() {
        http().get()
                .uri("/api/admin/providers")
                .headers(headers -> headers.setBasicAuth(ADMIN_USERNAME, ADMIN_PASSWORD))
                .exchange()
                .expectHeader()
                .valueEquals("X-Frame-Options", "DENY")
                .expectHeader()
                .valueEquals("Referrer-Policy", "no-referrer")
                .expectHeader()
                .valueEquals("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
    }

    @Test
    void stampsACorrelationIdentifierOnEveryAnswer() {
        http().get().uri("/api/admin/providers").exchange().expectHeader().exists("X-Janus-Correlation-Id");
    }

    // --- cross-site request forgery -------------------------------------------

    /**
     * Forgery works because a browser attaches cookies on its own. It never attaches an
     * Authorization header on its own, so a script presenting Basic credentials has nothing to
     * forge — and is not asked for a token it could not have.
     */
    @Test
    void doesNotDemandACsrfTokenFromACallerPresentingItsOwnCredentials() {
        http().post()
                .uri("/api/admin/providers")
                .headers(headers -> headers.setBasicAuth(ADMIN_USERNAME, ADMIN_PASSWORD))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(
                        """
                        {"name":"Spotify","slug":"csrf-probe","baseUrl":"https://api.spotify.com","enabled":true}""")
                .exchange()
                .expectStatus()
                .isOk();
    }

    /** A write arriving with neither a token nor credentials never reaches a handler. */
    @Test
    void refusesAWriteThatCarriesNeitherATokenNorCredentials() {
        http().post()
                .uri("/api/admin/providers")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    /**
     * The console's own case, which is the one CSRF protection exists for: a browser attaches the
     * session cookie by itself, so the write must additionally echo back a token that only a page
     * on this origin could have read.
     */
    @Test
    void refusesAConsoleWriteThatDoesNotEchoTheTokenBack() {
        var session = signInAsConsole();

        http().post()
                .uri("/api/admin/providers")
                .cookie(SESSION_COOKIE, session.session())
                .cookie(CSRF_COOKIE, session.csrf())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(providerNamed("forged"))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void acceptsAConsoleWriteThatEchoesTheTokenBack() {
        var session = signInAsConsole();

        http().post()
                .uri("/api/admin/providers")
                .cookie(SESSION_COOKIE, session.session())
                .cookie(CSRF_COOKIE, session.csrf())
                .header(CSRF_HEADER, session.csrf())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(providerNamed("console-write"))
                .exchange()
                .expectStatus()
                .isOk();
    }

    private static final String SESSION_COOKIE = "JANUS_SESSION";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private record ConsoleSession(String session, String csrf) {}

    /**
     * Signs in the way the console does, and keeps the two cookies a browser would then hold.
     *
     * <p>The opening GET is not ceremony: it is what puts the CSRF cookie in place, and it is why
     * asking "who am I" has to be reachable before anybody has signed in.
     */
    private ConsoleSession signInAsConsole() {
        var opened = http().get().uri("/api/admin/session").exchange().returnResult(Void.class);
        var opening = opened.getResponseCookies().getFirst(CSRF_COOKIE);
        org.assertj.core.api.Assertions.assertThat(opening)
                .describedAs("the token the console echoes back is handed out before sign-in")
                .isNotNull();

        var result = http().post()
                .uri("/api/admin/session")
                .cookie(CSRF_COOKIE, opening.getValue())
                .header(CSRF_HEADER, opening.getValue())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(ADMIN_USERNAME, ADMIN_PASSWORD))
                .exchange()
                .expectBody(String.class)
                .returnResult();

        // Carries what the endpoint actually said, so a failure here names the reason rather than
        // only the status.
        org.assertj.core.api.Assertions.assertThat(result.getStatus().value())
                .describedAs("sign-in answered: %s", result.getResponseBody())
                .isEqualTo(200);

        var cookies = result.getResponseCookies();
        org.assertj.core.api.Assertions.assertThat(cookies.getFirst(SESSION_COOKIE))
                .describedAs("a session cookie the browser will send back on its own")
                .isNotNull();

        // The token is handed out once and stays valid across the sign-in; only the session
        // identifier is replaced, which is what defeats a session fixed before the sign-in.
        var refreshed = cookies.getFirst(CSRF_COOKIE);
        return new ConsoleSession(
                cookies.getFirst(SESSION_COOKIE).getValue(),
                refreshed == null ? opening.getValue() : refreshed.getValue());
    }

    private static String providerNamed(String slug) {
        return """
               {"name":"Spotify","slug":"%s","baseUrl":"https://api.spotify.com","enabled":true}"""
                .formatted(slug);
    }
}
