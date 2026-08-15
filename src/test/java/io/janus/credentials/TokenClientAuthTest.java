package io.janus.credentials;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

/**
 * How Janus proves who it is at a token endpoint.
 *
 * <p>The step being defended is the one RFC 6749 §2.3.1 asks for and that is easy to miss, because
 * it changes nothing for most secrets: both halves are form-urlencoded before they are joined and
 * base64'd. It changes everything for a secret containing {@code +}, {@code /}, {@code =} or a
 * space, which is the shape a good many providers issue, and the symptom of getting it wrong is an
 * {@code invalid_client} that depends on which secret happened to be minted.
 */
class TokenClientAuthTest {

    private static String decoded(String clientId, String clientSecret) {
        return new String(
                Base64.getDecoder().decode(TokenClientAuth.basicCredentials(clientId, clientSecret)),
                StandardCharsets.UTF_8);
    }

    @Test
    void anOrdinaryPairIsUnchangedByTheEncoding() {
        assertThat(decoded("client-abc", "secret-xyz")).isEqualTo("client-abc:secret-xyz");
    }

    @Test
    void aSecretWithReservedCharactersIsEncodedBeforeItIsJoined() {
        assertThat(decoded("client", "a+b/c=")).isEqualTo("client:a%2Bb%2Fc%3D");
        // A space is a plus in this encoding, which is what the form-urlencoded algorithm says.
        assertThat(decoded("client", "two words")).isEqualTo("client:two+words");
    }

    /** A colon in the secret must not be read as a second separator by whoever decodes it. */
    @Test
    void aColonInEitherHalfSurvivesAsItsOwnCharacter() {
        assertThat(decoded("client", "sec:ret")).isEqualTo("client:sec%3Aret");
    }
}
