package io.janus.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The answer RFC 6749 §5.1 describes, field names included: any OAuth client library reads this
 * without being told anything about Janus.
 *
 * @param refreshToken absent when refresh tokens are switched off, which is legal and means "come
 *     back with your client credentials"
 */
public record TokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn,
        @JsonProperty("refresh_token") String refreshToken) {

    public static TokenResponse bearer(String accessToken, long expiresIn, String refreshToken) {
        return new TokenResponse(accessToken, "Bearer", expiresIn, refreshToken);
    }
}
