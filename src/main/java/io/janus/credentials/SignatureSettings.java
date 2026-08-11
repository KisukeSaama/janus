package io.janus.credentials;

import java.util.regex.Pattern;

/**
 * The recipe for signing an outbound request: what is signed, with what, and where the result goes.
 *
 * <p>Every part of this is per-API. Binance signs the query string and wants the signature appended
 * to it as another parameter; Coinbase signs {@code timestamp + method + path + body} and wants it in
 * a header, alongside a second header carrying the timestamp itself. Neither can be derived from the
 * other, so both are recorded — and the console offers each as a named preset, because a developer
 * meeting one of these should not have to work out its canonicalisation from the provider's docs.
 *
 * <p>What is not recorded is the key, which never leaves OpenBao and is only ever the MAC's key. See
 * {@link SignatureTemplate} for why the recipe cannot reach it.
 *
 * @param algorithm the keyed hash to compute
 * @param template what to sign
 * @param encoding how the computed bytes are written down
 * @param signatureHeader the header the signature travels in, or blank when it travels in the query
 * @param signatureParameter the query parameter it travels in, or blank when it travels in a header
 * @param timestampHeader a header to also carry the timestamp in, for the APIs that check it against
 *     their own clock. Blank when the API reads it from where the template already put it
 * @param timestampParameter the same, as a query parameter. Binance wants this, and wants it inside
 *     the signed query string — which it is, because it is added before that string is built
 */
public record SignatureSettings(
        SignatureAlgorithm algorithm,
        SignatureTemplate template,
        SignatureEncoding encoding,
        String signatureHeader,
        String signatureParameter,
        String timestampHeader,
        String timestampParameter) {

    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9-]{1,100}");
    /** A parameter travels in a URL, so anything needing encoding to survive is refused. */
    private static final Pattern PARAMETER_NAME = Pattern.compile("[A-Za-z0-9._~-]{1,100}");

    public SignatureSettings {
        signatureHeader = trimToNull(signatureHeader);
        signatureParameter = trimToNull(signatureParameter);
        timestampHeader = trimToNull(timestampHeader);
        timestampParameter = trimToNull(timestampParameter);
        if (encoding == null) encoding = SignatureEncoding.HEX;
    }

    /** Everything an administrator must have stated for a request to be signable. */
    public void validate() {
        if (algorithm == null) throw new IllegalArgumentException("A signing algorithm is required");
        if (template == null) throw new IllegalArgumentException("A signing recipe is required");

        // Both would send the same signature twice; neither would send it at all. The second is the
        // one worth guarding — the request would leave looking complete and be refused upstream.
        if ((signatureHeader == null) == (signatureParameter == null))
            throw new IllegalArgumentException(
                    "A signature travels either in a header or in a query parameter; state exactly one");
        if (signatureHeader != null && !HEADER_NAME.matcher(signatureHeader).matches())
            throw new IllegalArgumentException("A valid header name is required for the signature");
        if (signatureParameter != null
                && !PARAMETER_NAME.matcher(signatureParameter).matches())
            throw new IllegalArgumentException("A valid query parameter name is required for the signature");

        if (timestampHeader != null && !HEADER_NAME.matcher(timestampHeader).matches())
            throw new IllegalArgumentException("A valid header name is required for the timestamp");
        if (timestampParameter != null
                && !PARAMETER_NAME.matcher(timestampParameter).matches())
            throw new IllegalArgumentException("A valid query parameter name is required for the timestamp");
        if (timestampHeader != null && timestampParameter != null)
            throw new IllegalArgumentException("A timestamp travels in one place: a header or a query parameter");
    }

    /** Whether the signature is appended to the address rather than carried in a header. */
    public boolean signatureInQuery() {
        return signatureParameter != null;
    }

    /** Whether the timestamp has to be in the address before the string to sign is built. */
    public boolean timestampInQuery() {
        return timestampParameter != null;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
