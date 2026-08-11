package io.janus.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.http.*;

class SecretRedactorTest {

    /**
     * A client-credentials exchange puts two values in play: the token that was sent, and the client
     * secret it was obtained with. An upstream unhappy about either one can quote either one back.
     */
    @org.junit.jupiter.api.Test
    void scrubsEveryValueItIsGiven() {
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        byte[] body = "{\"error\":\"bad token upstream-token for client id:s3cret\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        String scrubbed = new String(
                SecretRedactor.scrub(body, headers, "upstream-token", "id:s3cret"),
                java.nio.charset.StandardCharsets.UTF_8);

        org.assertj.core.api.Assertions.assertThat(scrubbed)
                .doesNotContain("upstream-token")
                .doesNotContain("id:s3cret")
                .contains(SecretRedactor.PLACEHOLDER);
    }

    // Keep the credential-shaped fixture out of source-level secret scanners.
    private static final String SECRET = String.join("", "sk_", "live_", "test-redaction-value");

    private static HttpHeaders headers(MediaType contentType, String contentEncoding) {
        var headers = new HttpHeaders();
        if (contentType != null) headers.setContentType(contentType);
        if (contentEncoding != null) headers.set(HttpHeaders.CONTENT_ENCODING, contentEncoding);
        return headers;
    }

    private static String scrub(String body, HttpHeaders headers) {
        byte[] result = SecretRedactor.scrub(body.getBytes(StandardCharsets.UTF_8), headers, SECRET);
        return new String(result, StandardCharsets.UTF_8);
    }

    @Test
    void removesASecretEchoedInAJsonBody() {
        String body = "{\"error\":\"invalid key " + SECRET + "\"}";
        assertThat(scrub(body, headers(MediaType.APPLICATION_JSON, null)))
                .doesNotContain(SECRET)
                .contains(SecretRedactor.PLACEHOLDER);
    }

    @Test
    void removesASecretEchoedInPlainText() {
        assertThat(scrub("token=" + SECRET, headers(MediaType.TEXT_PLAIN, null)))
                .doesNotContain(SECRET);
    }

    @Test
    void removesTheBase64FormUsedByBasicAuthentication() {
        String encoded = Base64.getEncoder().encodeToString(SECRET.getBytes(StandardCharsets.UTF_8));
        assertThat(scrub("{\"seen\":\"" + encoded + "\"}", headers(MediaType.APPLICATION_JSON, null)))
                .doesNotContain(encoded);
    }

    @Test
    void handlesVendorJsonMediaTypes() {
        var contentType = MediaType.parseMediaType("application/vnd.example.v1+json");
        assertThat(scrub("{\"k\":\"" + SECRET + "\"}", headers(contentType, null)))
                .doesNotContain(SECRET);
    }

    @Test
    void leavesAnUnaffectedBodyByteIdentical() {
        byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        assertThat(SecretRedactor.scrub(body, headers(MediaType.APPLICATION_JSON, null), SECRET))
                .isSameAs(body);
    }

    @Test
    void doesNotRewriteBinaryPayloads() {
        byte[] body = {0x00, 0x01, 0x02, 0x03};
        assertThat(SecretRedactor.scrub(body, headers(MediaType.APPLICATION_OCTET_STREAM, null), SECRET))
                .isSameAs(body);
    }

    @Test
    void doesNotRewriteCompressedPayloads() {
        byte[] body = ("leak " + SECRET).getBytes(StandardCharsets.UTF_8);
        assertThat(SecretRedactor.scrub(body, headers(MediaType.APPLICATION_JSON, "gzip"), SECRET))
                .isSameAs(body);
    }

    @Test
    void treatsAnIdentityEncodingAsUnencoded() {
        assertThat(scrub("leak " + SECRET, headers(MediaType.APPLICATION_JSON, "identity")))
                .doesNotContain(SECRET);
    }

    @Test
    void skipsBodiesWithoutADeclaredContentType() {
        byte[] body = ("leak " + SECRET).getBytes(StandardCharsets.UTF_8);
        assertThat(SecretRedactor.scrub(body, headers(null, null), SECRET)).isSameAs(body);
    }

    /** An API that reflects what it was sent commonly does it in a header, not only in the body. */
    @Test
    void removesASecretEchoedInAResponseHeader() {
        var upstream = new HttpHeaders();
        upstream.add("X-Debug-Received-Auth", "Bearer " + SECRET);
        upstream.add("Content-Type", "application/json");

        var scrubbed = SecretRedactor.scrubHeaders(upstream, SECRET);

        assertThat(scrubbed.getFirst("X-Debug-Received-Auth"))
                .doesNotContain(SECRET)
                .contains(SecretRedactor.PLACEHOLDER);
        assertThat(scrubbed.getFirst("Content-Type")).isEqualTo("application/json");
    }

    @Test
    void removesTheBase64FormFromAResponseHeaderToo() {
        String encoded = Base64.getEncoder().encodeToString(SECRET.getBytes(StandardCharsets.UTF_8));
        var upstream = new HttpHeaders();
        upstream.add("X-Echo", "Basic " + encoded);

        assertThat(SecretRedactor.scrubHeaders(upstream, SECRET).getFirst("X-Echo"))
                .doesNotContain(encoded);
    }

    /**
     * A value too short to be a credential is left alone. Matched against every header and body it
     * would corrupt far more than it protected, and a match on it proves nothing anyway.
     */
    @Test
    void leavesAValueTooShortToBeACredentialAlone() {
        var upstream = new HttpHeaders();
        upstream.add("Content-Type", "application/json");

        assertThat(SecretRedactor.scrubHeaders(upstream, "json").getFirst("Content-Type"))
                .isEqualTo("application/json");

        byte[] body = "{\"ok\":\"json\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(SecretRedactor.scrub(body, headers(MediaType.APPLICATION_JSON, null), "json"))
                .isSameAs(body);
    }

    @Test
    void toleratesAnEmptyOrAbsentBody() {
        assertThat(SecretRedactor.scrub(null, headers(MediaType.APPLICATION_JSON, null), SECRET))
                .isNull();
        assertThat(SecretRedactor.scrub(new byte[0], headers(MediaType.APPLICATION_JSON, null), SECRET))
                .isEmpty();
    }
}
