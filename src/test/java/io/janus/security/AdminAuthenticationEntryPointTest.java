package io.janus.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.BadCredentialsException;
import tools.jackson.databind.ObjectMapper;

class AdminAuthenticationEntryPointTest {
    private final AdminAuthenticationEntryPoint entryPoint = new AdminAuthenticationEntryPoint(new ObjectMapper());

    @Test
    void refusesABrowserWithoutChallengingIt() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/admin/accounts");
        request.addHeader("Sec-Fetch-Mode", "cors");
        var response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new BadCredentialsException("no session"));

        // A challenge here is what makes the browser open its own sign-in dialog over the console.
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString()).contains("Not signed in");
    }

    @Test
    void challengesAScript() throws Exception {
        var response = new MockHttpServletResponse();

        entryPoint.commence(
                new MockHttpServletRequest("GET", "/api/admin/accounts"),
                response,
                new BadCredentialsException("no credentials"));

        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Basic realm=\"Janus\"");
        assertThat(response.getStatus()).isEqualTo(401);
    }
}
