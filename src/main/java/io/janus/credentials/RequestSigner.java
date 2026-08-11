package io.janus.credentials;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.HttpMethod;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Signs an outbound request with a stored secret, for the APIs that want proof rather than the key.
 *
 * <p>A signature covers the request as it will actually be sent, which is why this runs last: after
 * the route has been resolved into a target URI and after the body is settled. Anything added
 * afterwards would fall outside what was signed, and the upstream would reject a request that looks,
 * from here, perfectly well formed.
 *
 * <p>The secret never leaves this class. It arrives as the key of a MAC and is used as one; it is not
 * logged, not returned, and not written into the URI or the headers this produces.
 *
 * <p>Stateless, and deliberately not a bean: it holds nothing, configures nothing, and is used from
 * one place. Injecting it would add a constructor parameter to the busiest class in the gateway in
 * order to express a dependency on a pure function.
 */
public class RequestSigner {

    /**
     * What to add to a request so it will be accepted.
     *
     * @param uri the address to call, which may differ from the one handed in: some APIs want the
     *     timestamp and the signature appended to the query, and both must be there before it is sent
     * @param headers the headers to set, in the order they were decided
     */
    public record Signed(URI uri, Map<String, String> headers) {}

    /**
     * Signs at the current instant.
     *
     * @param storedSecret the {@code key:secret} pair from OpenBao; only the half after the colon signs
     */
    public Signed sign(SignatureSettings settings, String storedSecret, HttpMethod method, URI target, byte[] body) {
        return sign(settings, storedSecret, method, target, body, Instant.now());
    }

    /** As above, at a stated instant, so a test can assert an exact signature. */
    public Signed sign(
            SignatureSettings settings, String storedSecret, HttpMethod method, URI target, byte[] body, Instant now) {
        int separator = storedSecret == null ? -1 : storedSecret.indexOf(':');
        if (separator < 0) throw new IllegalStateException("The stored secret is not in the form key:secret");
        String secret = storedSecret.substring(separator + 1);

        long timestamp = settings.template().millis() ? now.toEpochMilli() : now.getEpochSecond();

        // The timestamp goes in first, because Binance and the APIs shaped like it sign the query
        // string with it included. Adding it afterwards would sign one address and send another.
        var builder = UriComponentsBuilder.fromUri(target);
        if (settings.timestampInQuery()) builder.queryParam(settings.timestampParameter(), timestamp);
        var signable = builder.build(true).toUri();

        var parts = new SignatureTemplate.Parts(
                method.name(),
                signable.getRawPath() == null ? "/" : signable.getRawPath(),
                signable.getRawQuery() == null ? "" : signable.getRawQuery(),
                body == null || body.length == 0 ? "" : new String(body, StandardCharsets.UTF_8),
                timestamp);
        String signature = settings.encoding()
                .encode(mac(settings.algorithm(), secret, settings.template().expand(parts)));

        var headers = new LinkedHashMap<String, String>();
        if (settings.timestampHeader() != null) headers.put(settings.timestampHeader(), Long.toString(timestamp));

        URI uri = signable;
        if (settings.signatureInQuery())
            uri = UriComponentsBuilder.fromUri(signable)
                    .queryParam(settings.signatureParameter(), signature)
                    .build(true)
                    .toUri();
        else headers.put(settings.signatureHeader(), signature);

        return new Signed(uri, headers);
    }

    private static byte[] mac(SignatureAlgorithm algorithm, String secret, String payload) {
        try {
            var mac = Mac.getInstance(algorithm.jcaName());
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm.jcaName()));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            // The algorithm is one of two constants, so an empty stored secret is the only way here.
            // Saying which of the two it was would say more than a caller should learn.
            throw new IllegalStateException("The stored secret could not be used to sign this request");
        }
    }
}
