package io.janus.oauth;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import io.janus.security.AuthenticationThrottle;
import io.janus.shared.CorrelationIdFilter;

/**
 * The token endpoint, in the shape every OAuth client already speaks: form-encoded in, JSON out,
 * {@code Bearer} tokens, and RFC 6749 error bodies.
 *
 * <p>It lives outside {@code /gateway} and outside {@code /api/admin} because it is neither: no
 * bearer token exists yet when it is called, and no console session has anything to do with it. Its
 * own filter chain lets it through unauthenticated, and it authenticates the client itself.
 *
 * <p>Repeated failures from one address are throttled here rather than by a filter. The endpoint
 * takes a secret, so without it the exchange would be a place to try keys at whatever rate the
 * network allows — the same protection the console and the gateway already have.
 */
@RestController
@RequestMapping("/oauth")
public class OAuthTokenController {
    private final OAuthTokenService tokens;
    private final AuthenticationThrottle throttle;

    public OAuthTokenController(OAuthTokenService tokens, AuthenticationThrottle throttle) {
        this.tokens = tokens;
        this.throttle = throttle;
    }

    @PostMapping(path = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public TokenResponse token(
            @RequestParam("grant_type") String grantType,
            @RequestParam(value = "client_id", required = false) String clientId,
            @RequestParam(value = "client_secret", required = false) String clientSecret,
            @RequestParam(value = "refresh_token", required = false) String refreshToken,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest request) {

        String client = key(request);
        if (throttle.isBlocked(client))
            throw new OAuthException(
                    "invalid_request", HttpStatus.TOO_MANY_REQUESTS, "Too many failed attempts. Wait and try again.");

        // RFC 6749 §2.3.1: a client may present its credentials in the body or as Basic. Both are
        // accepted, and the header wins, so a caller cannot smuggle a second identity past the one
        // it authenticated with.
        var basic = OAuthTokenService.basicCredentials(authorization);
        if (basic.isPresent()) {
            clientId = basic.get()[0];
            clientSecret = basic.get()[1];
        }

        try {
            var response =
                    switch (grantType) {
                        case "client_credentials" -> tokens.clientCredentials(clientId, clientSecret);
                        case "refresh_token" -> tokens.refresh(refreshToken);
                        default -> throw OAuthException.unsupportedGrantType(grantType);
                    };
            throttle.recordSuccess(client);
            return response;
        } catch (OAuthException ex) {
            // A malformed request is a mistake, not an attempt: only refused credentials count.
            if (ex.status == HttpStatus.UNAUTHORIZED || "invalid_grant".equals(ex.error))
                throttle.recordFailure(client);
            throw ex;
        }
    }

    /** RFC 7009. Answers 200 whether or not the token existed, so it cannot be used to probe. */
    @PostMapping(path = "/revoke", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public void revoke(@RequestParam("token") String token) {
        tokens.revoke(token);
    }

    @ExceptionHandler(OAuthException.class)
    ResponseEntity<Map<String, Object>> failure(OAuthException ex) {
        var body = new LinkedHashMap<String, Object>();
        body.put("error", ex.error);
        body.put("error_description", ex.getMessage());
        body.put("correlationId", CorrelationIdFilter.current());
        var response = ResponseEntity.status(ex.status).contentType(MediaType.APPLICATION_JSON);
        // RFC 6749 §5.2: a 401 from this endpoint carries a challenge.
        if (ex.status == HttpStatus.UNAUTHORIZED)
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"janus\"");
        return response.body(body);
    }

    /** Namespaced, so a client failing here cannot lock an administrator out of the console. */
    private static String key(HttpServletRequest request) {
        return "oauth:" + request.getRemoteAddr();
    }
}
