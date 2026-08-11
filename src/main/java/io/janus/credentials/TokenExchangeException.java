package io.janus.credentials;

/**
 * The upstream token endpoint did not hand over a token.
 *
 * <p>It carries a status and a sentence, never the provider's response body: a refusal from a token
 * endpoint routinely quotes back the client credentials it just rejected, and this message travels
 * to the caller and into the journal.
 */
public class TokenExchangeException extends RuntimeException {
    public TokenExchangeException(String message) {
        super(message);
    }
}
