package io.janus.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Makes the CSRF token exist, so the cookie carrying it is actually written.
 *
 * <p>Spring Security defers the token: it is only generated when something asks for its value, and
 * nothing does on a plain {@code GET}. The repository therefore never writes the cookie, the console
 * has nothing to send back, and every write is refused with a message that explains none of this.
 *
 * <p>Reading {@code getToken()} is the whole of the work. It looks like a filter that does nothing,
 * which is precisely why this comment is longer than the code: deleting it breaks sign-in.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token != null) token.getToken();
        chain.doFilter(request, response);
    }
}
