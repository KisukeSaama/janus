package io.janus.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

import io.janus.shared.ApiProblem;

/**
 * The difference between "you have not signed in" and "you may not do this".
 *
 * <p>Both arrive here as the same {@link AccessDeniedException}: the CSRF filter raises one for a
 * write with no token, and the authorization filter raises one for an account without a role. Only
 * the security context tells them apart, and answering the first one 403 would tell a console to
 * stop retrying at the moment signing in is exactly what would work.
 */
class ProblemAuthorizationHandlerTest {

    private final AdminAuthenticationEntryPoint entryPoint = new AdminAuthenticationEntryPoint(new ObjectMapper());
    private final ProblemAuthorizationHandler handler = new ProblemAuthorizationHandler(new ObjectMapper(), entryPoint);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void asksAnAnonymousCallerToSignInRatherThanRefusingItsCredentials() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(new AnonymousAuthenticationToken(
                        "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        var response = new MockHttpServletResponse();

        handler.handle(
                new MockHttpServletRequest("POST", "/api/admin/providers"),
                response,
                new AccessDeniedException("missing CSRF token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Not signed in");
    }

    /** No context at all is the same case: a chain that never established one has nobody to refuse. */
    @Test
    void treatsAnEmptyContextTheSameWay() throws Exception {
        var response = new MockHttpServletResponse();

        handler.handle(
                new MockHttpServletRequest("POST", "/api/admin/providers"),
                response,
                new AccessDeniedException("missing CSRF token"));

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void refusesAnAccountThatIsSignedInWithoutTheRoleTheEndpointAsksFor() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        "kisuke", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        var response = new MockHttpServletResponse();

        handler.handle(
                new MockHttpServletRequest("DELETE", "/api/admin/gateway/cache"),
                response,
                new AccessDeniedException("Access Denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getHeader(ApiProblem.HEADER)).isEqualTo("forbidden");
    }

    /** A response already on its way is left alone: a second document would corrupt the first. */
    @Test
    void writesNothingOverAnAnswerThatHasAlreadyLeft() throws Exception {
        var response = new MockHttpServletResponse();
        response.getOutputStream().write("partial".getBytes());
        response.flushBuffer();

        handler.handle(
                new MockHttpServletRequest("GET", "/api/admin/providers"),
                response,
                new AccessDeniedException("Access Denied"));

        assertThat(response.getContentAsString()).isEqualTo("partial");
    }
}
