package io.janus.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.*;
import org.springframework.mock.web.*;
import tools.jackson.databind.ObjectMapper;

import io.janus.audit.AuditAction;
import io.janus.audit.AuditActor;
import io.janus.audit.AuditService;

/**
 * The guess limiter in front of the control plane.
 *
 * <p>Its subtlety is what it declines to count. The console signs in with a cookie, so most 401s
 * here are expired sessions — a tab restored, a laptop reopened — and counting those would lock an
 * address out for having done nothing wrong.
 */
class AdminAuthenticationThrottleFilterTest {
    private final AuthenticationThrottle throttle = new AuthenticationThrottle(3, 300, 900);
    private final AuditService audit = Mockito.mock(AuditService.class);

    private final AdminAuthenticationThrottleFilter filter =
            new AdminAuthenticationThrottleFilter(throttle, audit, new ObjectMapper());

    private static MockHttpServletRequest request(String remoteAddress, boolean withCredentials) {
        var request = new MockHttpServletRequest("GET", "/api/admin/providers");
        request.setRemoteAddr(remoteAddress);
        if (withCredentials) request.addHeader(HttpHeaders.AUTHORIZATION, "Basic cm9vdDpodW50ZXIy");
        return request;
    }

    /** Runs the filter with a chain that answers with the given status, as the security chain would. */
    private MockHttpServletResponse run(MockHttpServletRequest request, int answeredWith) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(answeredWith));
        return response;
    }

    // --- what is counted -----------------------------------------------------

    @Test
    void countsARefusedSignInThatActuallyPresentedCredentials() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            run(request("10.0.0.1", true), HttpStatus.UNAUTHORIZED.value());
        }

        assertThat(throttle.isBlocked("admin:10.0.0.1")).isTrue();
        verify(audit, times(3))
                .recordAuthenticationDenied(
                        eq(AuditActor.ADMIN),
                        eq(AuditAction.ADMIN_AUTHENTICATION),
                        eq("GET"),
                        eq("/api/admin/providers"),
                        eq(401),
                        anyString());
    }

    /** An expired session is not a guess. Counting it would punish somebody for leaving a tab open. */
    @Test
    void doesNotCountA401FromARequestThatCarriedNoCredentials() throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            run(request("10.0.0.1", false), HttpStatus.UNAUTHORIZED.value());
        }

        assertThat(throttle.isBlocked("admin:10.0.0.1")).isFalse();
        verifyNoInteractions(audit);
    }

    @Test
    void aSuccessfulSignInClearsWhatWasCountedAgainstTheAddress() throws Exception {
        run(request("10.0.0.1", true), HttpStatus.UNAUTHORIZED.value());
        run(request("10.0.0.1", true), HttpStatus.UNAUTHORIZED.value());

        run(request("10.0.0.1", true), HttpStatus.OK.value());

        run(request("10.0.0.1", true), HttpStatus.UNAUTHORIZED.value());
        assertThat(throttle.isBlocked("admin:10.0.0.1")).isFalse();
    }

    // --- what a blocked client receives --------------------------------------

    /** Refused before the password is compared: that is the whole point of being in front. */
    @Test
    void refusesABlockedClientWithoutRunningTheRestOfTheChain() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            run(request("10.0.0.1", true), HttpStatus.UNAUTHORIZED.value());
        }

        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request("10.0.0.1", true), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getContentAsString())
                .contains("Too many failed sign-in attempts")
                .contains("correlationId");
        assertThat(chain.getRequest()).isNull();
    }

    /** One address guessing must not lock out everybody else behind the same proxy. */
    @Test
    void blocksOneAddressWithoutBlockingAnother() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            run(request("10.0.0.1", true), HttpStatus.UNAUTHORIZED.value());
        }

        assertThat(throttle.isBlocked("admin:10.0.0.1")).isTrue();
        assertThat(throttle.isBlocked("admin:10.0.0.2")).isFalse();
        assertThat(run(request("10.0.0.2", true), HttpStatus.OK.value()).getStatus())
                .isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void letsAnOrdinaryRequestThrough() throws Exception {
        var response = run(request("10.0.0.1", false), HttpStatus.OK.value());

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(throttle.isBlocked("admin:10.0.0.1")).isFalse();
    }
}
