package io.janus.mcp;

import java.util.*;

import jakarta.servlet.http.HttpServletRequest;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import io.janus.oauth.OAuthException;
import io.janus.oauth.TokenResponse;
import io.janus.security.AuthenticationThrottle;
import io.janus.shared.CorrelationIdFilter;

/**
 * The public half of the MCP authorisation server: the two discovery documents, registration, the
 * authorisation endpoint, and the token and revocation endpoints.
 *
 * <p>None of it is behind authentication, because none of it can be: an MCP client arrives knowing
 * only the address of {@code /mcp}, and finds everything else from there (RFC 9728, then RFC 8414).
 * The one step that needs a person is the consent, and it happens in the console, not here.
 */
@RestController
public class McpOAuthController {
    private final McpOAuthService oauth;
    private final AuthenticationThrottle throttle;

    public McpOAuthController(McpOAuthService oauth, AuthenticationThrottle throttle) {
        this.oauth = oauth;
        this.throttle = throttle;
    }

    /**
     * RFC 9728. Served both at the root and under the resource's path, since clients look in either
     * place depending on which revision of the specification they were written against.
     */
    @GetMapping({"/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp"})
    public Map<String, Object> protectedResource() {
        var body = new LinkedHashMap<String, Object>();
        body.put("resource", oauth.resource());
        body.put("authorization_servers", List.of(oauth.issuer()));
        body.put("scopes_supported", List.of(McpOAuthService.SCOPE));
        body.put("bearer_methods_supported", List.of("header"));
        body.put("resource_name", "Janus");
        return body;
    }

    /** RFC 8414, and the OpenID location some clients try first. */
    @GetMapping({"/.well-known/oauth-authorization-server", "/.well-known/openid-configuration"})
    public Map<String, Object> authorizationServer() {
        String issuer = oauth.issuer();
        var body = new LinkedHashMap<String, Object>();
        body.put("issuer", issuer);
        body.put("authorization_endpoint", issuer + "/oauth/mcp/authorize");
        body.put("token_endpoint", issuer + "/oauth/mcp/token");
        body.put("registration_endpoint", issuer + "/oauth/mcp/register");
        body.put("revocation_endpoint", issuer + "/oauth/mcp/revoke");
        body.put("scopes_supported", List.of(McpOAuthService.SCOPE));
        body.put("response_types_supported", List.of("code"));
        body.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
        body.put("code_challenge_methods_supported", List.of("S256"));
        // Public clients only: an assistant installed on a laptop has nowhere to keep a secret.
        body.put("token_endpoint_auth_methods_supported", List.of("none"));
        body.put("revocation_endpoint_auth_methods_supported", List.of("none"));
        body.put("authorization_response_iss_parameter_supported", true);
        return body;
    }

    /** What a client says about itself (RFC 7591 §2). Everything else it sends is ignored. */
    public record RegistrationRequest(
            @JsonProperty("client_name") String clientName,
            @JsonProperty("redirect_uris") List<String> redirectUris) {}

    /**
     * RFC 7591. Whatever authentication method the client asked for, it is registered as public and
     * told so: the specification lets a server replace what it will not honour.
     */
    @PostMapping(path = "/oauth/mcp/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> register(@RequestBody RegistrationRequest request) {
        var registered = oauth.register(request.clientName(), request.redirectUris());
        var body = new LinkedHashMap<String, Object>();
        body.put("client_id", registered.clientId());
        body.put("client_id_issued_at", registered.issuedAt().getEpochSecond());
        body.put("client_name", registered.clientName());
        body.put("redirect_uris", registered.redirectUris());
        body.put("grant_types", List.of("authorization_code", "refresh_token"));
        body.put("response_types", List.of("code"));
        body.put("token_endpoint_auth_method", "none");
        body.put("scope", McpOAuthService.SCOPE);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** Sends the browser to the console's consent screen, or back to the client with a refusal. */
    @GetMapping("/oauth/mcp/authorize")
    public ResponseEntity<Void> authorize(
            @RequestParam(value = "response_type", required = false) String responseType,
            @RequestParam(value = "client_id", required = false) String clientId,
            @RequestParam(value = "redirect_uri", required = false) String redirectUri,
            @RequestParam(value = "code_challenge", required = false) String codeChallenge,
            @RequestParam(value = "code_challenge_method", required = false) String codeChallengeMethod,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "resource", required = false) String resource) {
        var target = oauth.authorize(new McpOAuthService.AuthorizeRequest(
                responseType, clientId, redirectUri, codeChallenge, codeChallengeMethod, state, resource));
        return ResponseEntity.status(HttpStatus.FOUND).location(target).build();
    }

    @PostMapping(path = "/oauth/mcp/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<TokenResponse> token(
            @RequestParam(value = "grant_type", required = false) String grantType,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "redirect_uri", required = false) String redirectUri,
            @RequestParam(value = "client_id", required = false) String clientId,
            @RequestParam(value = "code_verifier", required = false) String codeVerifier,
            @RequestParam(value = "refresh_token", required = false) String refreshToken,
            @RequestParam(value = "resource", required = false) String resource,
            HttpServletRequest request) {
        String client = "mcp-oauth:" + request.getRemoteAddr();
        long blockedFor = throttle.blockedForSeconds(client);
        if (blockedFor > 0)
            throw new OAuthException(
                    "invalid_request",
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many failed attempts. Wait and try again.",
                    blockedFor);
        try {
            var response =
                    switch (grantType == null ? "" : grantType) {
                        case "authorization_code" ->
                            oauth.exchangeCode(code, redirectUri, clientId, codeVerifier, resource);
                        case "refresh_token" -> oauth.refresh(refreshToken, clientId, resource);
                        default ->
                            throw new OAuthException(
                                    "unsupported_grant_type",
                                    HttpStatus.BAD_REQUEST,
                                    "Supported grant types are authorization_code and refresh_token");
                    };
            throttle.recordSuccess(client);
            // RFC 6749 §5.1: a token response is never to be cached by anything in between.
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .body(response);
        } catch (OAuthException ex) {
            if ("invalid_grant".equals(ex.error)) throttle.recordFailure(client);
            throw ex;
        }
    }

    /** RFC 7009. Answers 200 whether or not the token existed, so it cannot be used to probe. */
    @PostMapping(path = "/oauth/mcp/revoke", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public void revoke(@RequestParam(value = "token", required = false) String token) {
        oauth.revoke(token);
    }

    @ExceptionHandler(OAuthException.class)
    ResponseEntity<Map<String, Object>> failure(OAuthException ex) {
        var body = new LinkedHashMap<String, Object>();
        body.put("error", ex.error);
        body.put("error_description", ex.getMessage());
        body.put("correlationId", CorrelationIdFilter.current());
        var response = ResponseEntity.status(ex.status)
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore());
        if (ex.retryAfterSeconds > 0) response.header(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds));
        return response.body(body);
    }
}
