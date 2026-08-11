package io.janus.gateway;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.*;

import io.janus.shared.CorrelationIdFilter;

/**
 * What a browser is told it may do, before it is told whether it may.
 *
 * <p>A preflight carries no credentials — no bearer token, no application identifier, nothing that
 * says who is asking. There is therefore nothing to decide it against, and pretending otherwise
 * would only mean refusing every cross-origin call. So the answer here is permissive by design: any
 * origin, echoed back rather than {@code *}, with credentials off.
 *
 * <p><strong>A preflight is not an authorisation.</strong> It says the browser may send the request;
 * the request itself still has to carry a token that works, from an origin the service declared, to
 * a destination an active grant admits it to. This class widens nothing.
 *
 * <p>Credentials are refused deliberately, and it matters: with {@code allowCredentials} off, a
 * browser will not attach cookies to a gateway call. The console's session cookie therefore cannot
 * be replayed at the gateway by a page on another origin, whatever else is true.
 */
@Component
public class GatewayCorsConfigurationSource implements CorsConfigurationSource {

    /** Everything Janus states about a call, so a browser client can read its own rate limit. */
    private static final List<String> EXPOSED = List.of(
            CorrelationIdFilter.RESPONSE_HEADER,
            GatewayTrafficService.CACHE_HEADER,
            GatewayTrafficService.LIMIT_HEADER,
            GatewayTrafficService.REMAINING_HEADER,
            GatewayTrafficService.RESET_HEADER,
            GatewayTrafficService.ATTEMPTS_HEADER,
            HttpHeaders.RETRY_AFTER,
            HttpHeaders.AGE);

    @Override
    public CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
        var configuration = new CorsConfiguration();
        // The origin is echoed, never '*': a wildcard cannot be narrowed later, and some clients
        // treat the two differently even without credentials.
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"));
        // Bearer tokens travel in Authorization; the two X-Janus-* headers are the static key. A
        // browser also needs the content type and whatever the upstream API expects to be told.
        configuration.setAllowedHeaders(List.of(
                HttpHeaders.AUTHORIZATION,
                HttpHeaders.CONTENT_TYPE,
                HttpHeaders.ACCEPT,
                HttpHeaders.ACCEPT_LANGUAGE,
                HttpHeaders.IF_NONE_MATCH,
                HttpHeaders.IF_MODIFIED_SINCE,
                CorrelationIdFilter.REQUEST_HEADER,
                "X-Janus-Application-Id",
                "X-Janus-Api-Key"));
        configuration.setExposedHeaders(EXPOSED);
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(600L);
        return configuration;
    }
}
