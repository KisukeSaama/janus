package io.janus.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps the gateway's authentication filter inside the security chain, where it was always meant to
 * be.
 *
 * <p>Spring Boot registers every {@code Filter} bean with the servlet container as well. A filter
 * that is also wired into a {@code SecurityFilterChain} therefore runs <em>twice over</em>: once at
 * the front of the container's chain, before Spring Security's own filters, and once where it was
 * placed — except {@code OncePerRequestFilter} makes the second run a no-op, so the one that
 * actually decides is the one that runs first.
 *
 * <p>That ordering breaks CORS outright. A browser preflight carries no credentials by design, so
 * the filter answers 401 before {@code CorsFilter} ever sees it, and no amount of CORS configuration
 * can help: every cross-origin call fails at the preflight.
 *
 * <p>Disabling the container registration leaves exactly one execution, in the chain, after CORS.
 * The other filters stay auto-registered deliberately — correlation identifiers, the URI guard, the
 * client rate limit and the body size limit all have to apply to every request, including the ones
 * security refuses.
 */
@Configuration
public class GatewayFilterRegistration {

    @Bean
    FilterRegistrationBean<ApiKeyAuthenticationFilter> apiKeyFilterRegistration(ApiKeyAuthenticationFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
