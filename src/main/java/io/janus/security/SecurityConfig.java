package io.janus.security;

import java.util.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.*;
import tools.jackson.databind.ObjectMapper;

import io.janus.audit.AuditService;
import io.janus.gateway.GatewayCorsConfigurationSource;
import io.janus.shared.CorrelationIdFilter;

@Configuration
public class SecurityConfig {

    /** Primary because the administrator realm adds a caching decorator that is also a {@link PasswordEncoder}. */
    @Bean
    @Primary
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    AdminPasswordEncoder adminPasswordEncoder(PasswordEncoder encoder) {
        return new AdminPasswordEncoder(encoder);
    }

    // Accounts live in the database: io.janus.accounts.AccountUserDetailsService is the
    // UserDetailsService injected below. The single in-memory account this configuration used to
    // build is now the bootstrap row posted by V7__accounts.sql.

    /**
     * Gateway chain. Stateless, and callable from anything: a server holding the static key, or a
     * browser page holding a bearer token from {@code /oauth/token}.
     *
     * <p>Two things make the browser case safe. Credentials are off in the CORS configuration, so a
     * page on another origin cannot make a browser attach the console's session cookie to a gateway
     * call. And nothing on this chain reads a session at all — see the security context repository
     * below — so a cookie that did arrive would authenticate nobody.
     */
    @Bean
    @Order(1)
    SecurityFilterChain gateway(
            HttpSecurity http, ApiKeyAuthenticationFilter apiKey, GatewayCorsConfigurationSource gatewayCors)
            throws Exception {
        return http.securityMatcher("/gateway/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(gatewayCors))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // The console signs people in with a cookie. Without this, a browser that holds one
                // would satisfy `authenticated()` on the gateway without presenting any application
                // credential at all — an administrator's browser could call every proxied API.
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(RequestCacheConfigurer::disable)
                .authorizeHttpRequests(requests -> requests
                        // A real preflight never reaches here: CorsFilter sits earlier in the chain
                        // and answers it. What is refused is a bare OPTIONS, which Spring MVC would
                        // otherwise answer with a route's allowed methods without consulting a grant.
                        .requestMatchers(HttpMethod.OPTIONS, "/gateway/**")
                        .denyAll()
                        .anyRequest()
                        .authenticated())
                .addFilterBefore(apiKey, BasicAuthenticationFilter.class)
                .headers(headers -> headers.frameOptions(frame -> frame.deny())
                        .referrerPolicy(
                                referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .build();
    }

    /**
     * Token chain. Open to anyone, because no token exists yet when it is called: the endpoint
     * authenticates the client itself, from the client id and secret it is given, and throttles the
     * attempts. Callable from a browser for the same reason the gateway is.
     */
    @Bean
    @Order(2)
    SecurityFilterChain oauth(HttpSecurity http, GatewayCorsConfigurationSource gatewayCors) throws Exception {
        return http.securityMatcher("/oauth/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(gatewayCors))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(RequestCacheConfigurer::disable)
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .headers(headers -> headers.frameOptions(frame -> frame.deny())
                        .referrerPolicy(
                                referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .build();
    }

    /**
     * The manager the sign-in endpoint authenticates against, and the one this chain uses.
     *
     * <p>Declared here rather than left to auto-configuration, so the caching encoder reaches this
     * realm and only this realm: gateway keys have a cache of their own, with its own invalidation,
     * and the encoder that mints new ones must stay the plain one.
     */
    @Bean
    AuthenticationManager authenticationManager(UserDetailsService users, AdminPasswordEncoder adminPasswordEncoder) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(adminPasswordEncoder);
        return new ProviderManager(provider);
    }

    /**
     * Administration chain. A session cookie for the console, HTTP Basic for a script, and closed by
     * default.
     *
     * <p>Both doors reach the same accounts. The cookie is what a browser should use — it is
     * HttpOnly, so no script on the origin can read it back, which is more than the console could
     * promise while it held a password in memory. Basic stays for the automation that has no session
     * to keep.
     *
     * <p>CSRF protection is therefore <em>on</em>, and skipped for requests carrying an
     * {@code Authorization} header. That is not a hole: cross-site forgery works because a browser
     * attaches cookies on its own, and it never attaches an Authorization header on its own. A script
     * with Basic credentials has nothing to forge.
     */
    @Bean
    @Order(3)
    SecurityFilterChain admin(
            HttpSecurity http, AuthenticationThrottle throttle, AuditService audit, ObjectMapper mapper)
            throws Exception {
        var entryPoint = new AdminAuthenticationEntryPoint(mapper);
        return http.csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        // The default handler XORs the token, so the value in the cookie is not the
                        // value to send back. A console that reads its own cookie and returns it —
                        // which is the only thing it can do — would be refused every time.
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers(request -> request.getHeader(HttpHeaders.AUTHORIZATION) != null))
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(requests -> requests.requestMatchers("/actuator/health", "/actuator/health/**")
                        .permitAll()
                        // Answering "who am I" is how the console decides whether to show a sign-in
                        // screen, and asking has to be possible before the answer is "nobody". It is
                        // also what puts the CSRF cookie in place. The endpoint answers 401 itself.
                        .requestMatchers(HttpMethod.GET, "/api/admin/session")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/admin/session")
                        .permitAll()
                        // Process-level operational series, tagged by provider slug, so they describe
                        // every owner's traffic at once. Nobody's registry is anybody else's to read,
                        // and no role is here to supervise, so this stays with the one account that
                        // answers for the deployment itself.
                        .requestMatchers("/actuator/metrics", "/actuator/metrics/**")
                        .hasRole("SUPER_ADMIN")
                        // Dropping every stored response affects the whole process rather than one
                        // registry. It discloses nothing — a cache purge answers with a count — but
                        // it is felt by everybody, so it stays with the account that answers for the
                        // deployment. Purging one's own destination is on the provider itself.
                        .requestMatchers(HttpMethod.DELETE, "/api/admin/gateway/cache")
                        .hasRole("SUPER_ADMIN")
                        // Who may sign in. AccountService decides which role may act on which
                        // account: an administrator appoints, a super administrator arbitrates.
                        .requestMatchers("/api/admin/accounts/**")
                        .hasAnyRole("SUPER_ADMIN", "ADMIN")
                        // Everything else is the registry, and every one of its queries is scoped to
                        // the caller by AccessScope. Authentication is therefore the whole of the
                        // access decision here: there is nothing a role could widen.
                        .requestMatchers("/api/admin/**")
                        .authenticated()
                        .anyRequest()
                        .denyAll())
                .addFilterBefore(
                        new AdminAuthenticationThrottleFilter(throttle, audit, mapper), BasicAuthenticationFilter.class)
                // After the CSRF filter, so the deferred token has been placed on the request by then.
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .httpBasic(basic -> basic.authenticationEntryPoint(entryPoint))
                // The same refusal whether the credentials were wrong (Basic answers that itself) or
                // never presented at all, which is what an ended console session looks like.
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint))
                .headers(headers -> headers.frameOptions(frame -> frame.deny())
                        .referrerPolicy(
                                referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .contentSecurityPolicy(
                                csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .permissionsPolicyHeader(permissions ->
                                permissions.policy("geolocation=(), camera=(), microphone=(), payment=()")))
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${janus.cors-origins}") String origins) {
        var allowed = Arrays.stream(origins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
        if (allowed.contains("*"))
            throw new IllegalStateException("janus.cors-origins must name explicit origins; '*' is not accepted");

        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowed);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", CorrelationIdFilter.REQUEST_HEADER));
        configuration.setExposedHeaders(List.of(CorrelationIdFilter.RESPONSE_HEADER));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/admin/**", configuration);
        return source;
    }
}
