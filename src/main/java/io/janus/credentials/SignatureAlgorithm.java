package io.janus.credentials;

/**
 * The keyed hashes Janus will sign a request with.
 *
 * <p>Two, because two is what signed-request APIs actually use. These are Janus's own spelling of the
 * JCA algorithms underneath rather than names agreed with anyone: an HMAC has no wire representation
 * to disagree about, only a result.
 */
public enum SignatureAlgorithm {
    /** HMAC with SHA-256. What almost every signed-request API asks for. */
    HMAC_SHA256("HmacSHA256"),
    /** HMAC with SHA-512, for the few that ask for it — Kraken among them. */
    HMAC_SHA512("HmacSHA512");

    private final String jca;

    SignatureAlgorithm(String jca) {
        this.jca = jca;
    }

    /** The name to ask the JCA for. */
    public String jcaName() {
        return jca;
    }
}
