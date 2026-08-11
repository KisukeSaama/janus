package io.janus.credentials;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.janus.accounts.TestAccount;
import io.janus.providers.Provider;

class CredentialRequestTest {

    private static CredentialRequest request(
            AuthType authType, String headerName, String queryParameter, String tokenUrl, String secret) {
        return new CredentialRequest(
                "key",
                UUID.randomUUID(),
                authType,
                headerName,
                queryParameter,
                tokenUrl,
                null,
                null,
                secret,
                null,
                true);
    }

    @Test
    void aHeaderKeyNeedsAUsableHeaderName() {
        assertThatThrownBy(() -> request(AuthType.API_KEY_HEADER, null, null, null, "abc")
                        .validate())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(AuthType.API_KEY_HEADER, "not a header", null, null, "abc")
                        .validate())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatNoException().isThrownBy(() -> request(AuthType.API_KEY_HEADER, "X-Api-Key", null, null, "abc")
                .validate());
    }

    /** The name goes into a URL, so anything that would need encoding to survive is refused. */
    @Test
    void aQueryKeyNeedsAParameterNameThatSurvivesAUrl() {
        assertThatThrownBy(() ->
                        request(AuthType.API_KEY_QUERY, null, null, null, "abc").validate())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(AuthType.API_KEY_QUERY, null, "api key", null, "abc")
                        .validate())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatNoException().isThrownBy(() -> request(AuthType.API_KEY_QUERY, null, "api_key", null, "abc")
                .validate());
    }

    @Test
    void anExchangeNeedsATokenEndpoint() {
        assertThatThrownBy(() -> request(AuthType.OAUTH2_CLIENT_CREDENTIALS, null, null, null, "id:secret")
                        .validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token endpoint");
    }

    @Test
    void anExchangeNeedsItsSecretAsClientIdAndClientSecret() {
        assertThatThrownBy(() -> request(
                                AuthType.OAUTH2_CLIENT_CREDENTIALS,
                                null,
                                null,
                                "https://accounts.spotify.com/api/token",
                                "just-one-value")
                        .validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("client_id:client_secret");

        assertThatNoException().isThrownBy(() -> request(
                        AuthType.OAUTH2_CLIENT_CREDENTIALS,
                        null,
                        null,
                        "https://accounts.spotify.com/api/token",
                        "abc123:s3cret")
                .validate());
    }

    /**
     * A value typed here would be written to OpenBao and never sent anywhere, which is the one thing
     * an operator would not expect from having typed it. Refused rather than quietly dropped.
     */
    @Test
    void anOpenApiTakesNoSecret() {
        assertThatThrownBy(() -> request(AuthType.NONE, null, null, null, "abc").validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("takes no secret");
        assertThatNoException()
                .isThrownBy(() -> request(AuthType.NONE, null, null, null, null).validate());
    }

    /** An update that omits the secret keeps what OpenBao holds, so its shape cannot be checked. */
    @Test
    void anUpdateWithoutASecretDoesNotCheckTheSecretsShape() {
        assertThatNoException().isThrownBy(() -> request(
                        AuthType.OAUTH2_CLIENT_CREDENTIALS, null, null, "https://accounts.spotify.com/api/token", null)
                .validate());
    }

    /**
     * Changing strategy has to clear what belonged to the previous one, or the database's own check
     * constraints refuse a perfectly legitimate edit — and the record would read as if an exchange
     * were still configured.
     */
    @Test
    void changingTheStrategyClearsTheSettingsOfTheOldOne() {
        var provider = new Provider(
                TestAccount.owner(),
                "Spotify",
                "spotify",
                "https://api.spotify.com",
                true,
                new Provider.TrafficPolicy(true, 0, 0, 0));
        var credential = new Credential(
                provider,
                "spotify",
                new Credential.Strategy(
                        AuthType.OAUTH2_CLIENT_CREDENTIALS,
                        null,
                        null,
                        "https://accounts.spotify.com/api/token",
                        "playlist-read-private",
                        TokenClientAuth.POST),
                null,
                true);
        assertThat(credential.getTokenUrl()).isNotNull();

        credential.describe("spotify", Credential.Strategy.of(AuthType.BEARER), null, true);

        assertThat(credential.getTokenUrl()).isNull();
        assertThat(credential.getTokenScopes()).isNull();
        assertThat(credential.getTokenClientAuth()).isNull();
    }

    /** Basic is what RFC 6749 obliges every token endpoint to accept, so it is what Janus assumes. */
    @Test
    void anExchangeWithoutAStatedClientAuthenticationUsesBasic() {
        var provider = new Provider(
                TestAccount.owner(),
                "Spotify",
                "spotify",
                "https://api.spotify.com",
                true,
                new Provider.TrafficPolicy(true, 0, 0, 0));
        var credential = new Credential(
                provider,
                "spotify",
                new Credential.Strategy(
                        AuthType.OAUTH2_CLIENT_CREDENTIALS,
                        null,
                        null,
                        "https://accounts.spotify.com/api/token",
                        null,
                        null),
                null,
                true);

        assertThat(credential.getTokenClientAuth()).isEqualTo(TokenClientAuth.BASIC);
    }
}
