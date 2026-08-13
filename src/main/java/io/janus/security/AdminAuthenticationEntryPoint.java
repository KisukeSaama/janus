package io.janus.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.ObjectMapper;

import io.janus.shared.ApiProblem;
import io.janus.shared.ErrorCode;

/**
 * How the administration chain refuses a request that carried no usable credentials.
 *
 * <p>Spring's Basic entry point ends every 401 with {@code WWW-Authenticate: Basic}, and a browser
 * that reads that header answers it itself, with its own sign-in dialog. That dialog belongs to the
 * browser rather than to the page, so the console cannot dismiss it or explain it — it simply
 * appears over the console the moment a session ends, which is exactly when signing out is expected
 * to have worked.
 *
 * <p>So the challenge is sent only to callers that are not a page. Browsers stamp {@code
 * Sec-Fetch-Mode} on every request they make, including {@code fetch}; the scripts HTTP Basic is
 * kept for do not, and they still receive the challenge and the realm as before. Either way the
 * status is the same 401, with a problem document the console can read.
 */
public class AdminAuthenticationEntryPoint implements AuthenticationEntryPoint {
    /** Set by every browser on every fetch, XHR, and navigation; absent from curl and its kind. */
    private static final String BROWSER_MARKER = "Sec-Fetch-Mode";

    private static final String CHALLENGE = "Basic realm=\"Janus\", charset=\"UTF-8\"";

    private final ObjectMapper mapper;

    public AdminAuthenticationEntryPoint(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
            throws IOException {
        if (request.getHeader(BROWSER_MARKER) == null) response.setHeader(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE);

        ApiProblem.write(response, mapper, HttpStatus.UNAUTHORIZED, ErrorCode.NOT_SIGNED_IN, "Not signed in");
    }
}
