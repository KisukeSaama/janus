package io.janus.oauth;

import org.springframework.http.HttpStatus;

/**
 * A refusal in the shape RFC 6749 §5.2 defines, because every OAuth client already knows how to read
 * it. The description says what to change and never why the credentials failed: "no such client" and
 * "wrong secret" are the same answer, or the endpoint becomes a way to enumerate applications.
 */
public class OAuthException extends RuntimeException {
    /** {@code invalid_request}, {@code invalid_client}, {@code invalid_grant}, … */
    public final String error;

    public final HttpStatus status;

    public OAuthException(String error, HttpStatus status, String description) {
        super(description);
        this.error = error;
        this.status = status;
    }

    public static OAuthException invalidRequest(String description) {
        return new OAuthException("invalid_request", HttpStatus.BAD_REQUEST, description);
    }

    /** Both an unknown client and a wrong secret. The caller learns that the pair did not work. */
    public static OAuthException invalidClient() {
        return new OAuthException("invalid_client", HttpStatus.UNAUTHORIZED, "Client authentication failed");
    }

    public static OAuthException invalidGrant(String description) {
        return new OAuthException("invalid_grant", HttpStatus.BAD_REQUEST, description);
    }

    public static OAuthException unsupportedGrantType(String grantType) {
        return new OAuthException(
                "unsupported_grant_type",
                HttpStatus.BAD_REQUEST,
                "Supported grant types are client_credentials and refresh_token, not " + grantType);
    }
}
