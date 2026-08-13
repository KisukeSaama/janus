package io.janus.security;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
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
 * requires, and the write that arrives with no session and no CSRF token at all.
 *
 * <p>That last one is why an anonymous caller is handed back to the entry point rather than answered
 * here. {@code CsrfFilter} runs before {@link
 * org.springframework.security.web.access.ExceptionTranslationFilter} and so calls this handler
 * directly, skipping the one rule that filter applies first: a caller who has presented nothing is
 * told to authenticate, not that its credentials are insufficient. Answering 403 to a request that
 * carried no credentials tells a console to stop retrying when signing in is exactly what would
 * work.
 *
 * @param unauthenticated the chain's own way of asking for credentials, which differs between a
 *                        console session and a gateway key
 */
record ProblemAuthorizationHandler(ObjectMapper mapper, AuthenticationEntryPoint unauthenticated)
        implements AccessDeniedHandler {

    /** Anonymous and null are the same answer here: nobody has presented anything yet. */
    private static final AuthenticationTrustResolver TRUST = new AuthenticationTrustResolverImpl();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException denied)
            throws IOException, ServletException {
        if (response.isCommitted()) return;

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || TRUST.isAnonymous(authentication)) {
            unauthenticated.commence(
                    request, response, new InsufficientAuthenticationException(denied.getMessage(), denied));
            return;
        }

        ApiProblem.write(
                response,
                mapper,
                HttpStatus.FORBIDDEN,
                ErrorCode.FORBIDDEN,
                "These credentials are not permitted to make this request");
    }
}
