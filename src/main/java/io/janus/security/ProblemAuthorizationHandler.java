package io.janus.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.ObjectMapper;

import io.janus.shared.ApiProblem;
import io.janus.shared.ErrorCode;

/**
 * How Spring Security itself refuses a request, in the same shape as everything else Janus refuses.
 *
 * <p>Without this, a refusal that the framework raises rather than a filter or a controller falls
 * through to the servlet container's error page. That answer has different members under different
 * names, no {@code code}, and no correlation identifier — one document out of the whole API surface
 * that a client's error handling cannot read, arriving exactly when something has gone wrong.
 *
 * <p>Little reaches it. On the gateway chain it is the bare {@code OPTIONS} that CORS preflight did
 * not already answer; on the administration chain it is an account without the role an endpoint
 * requires. Both are worth stating properly.
 *
 * @param unauthenticated what to call a request that carried no usable credentials, which differs
 *                        between a console session and a gateway key
 */
record ProblemAuthorizationHandler(ObjectMapper mapper, ErrorCode unauthenticated)
        implements AuthenticationEntryPoint, AccessDeniedHandler {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
            throws IOException {
        if (response.isCommitted()) return;
        ApiProblem.write(response, mapper, HttpStatus.UNAUTHORIZED, unauthenticated, "Valid credentials are required");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException denied)
            throws IOException {
        if (response.isCommitted()) return;
        ApiProblem.write(
                response,
                mapper,
                HttpStatus.FORBIDDEN,
                ErrorCode.FORBIDDEN,
                "These credentials are not permitted to make this request");
    }
}
