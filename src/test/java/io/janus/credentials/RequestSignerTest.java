package io.janus.credentials;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.*;
import org.springframework.http.HttpMethod;

/**
 * Signing a request rather than sending the key with it.
 *
 * <p>What is asserted here is mostly about what a signature covers, because that is what an upstream
 * checks and what a mistake here would break silently: a request that leaves looking complete and is
 * refused for reasons no log explains. So each part of the recipe is shown to change the result, and
 * the key half of the stored pair is shown not to.
 */
class RequestSignerTest {
    private final RequestSigner signer = new RequestSigner();
    private static final Instant NOW = Instant.ofEpochSecond(1_700_000_000);

    /** Coinbase's shape: everything in headers, the path and body signed, seconds. */
    private static SignatureSettings coinbase() {
        return new SignatureSettings(
                SignatureAlgorithm.HMAC_SHA256,
                new SignatureTemplate(SignatureTemplate.TIMESTAMP_METHOD_PATH_BODY),
                SignatureEncoding.HEX,
                "CB-ACCESS-SIGN",
                null,
                "CB-ACCESS-TIMESTAMP",
                null);
    }

    /** Binance's shape: the query string signed, both values appended to it, milliseconds. */
    private static SignatureSettings binance() {
        return new SignatureSettings(
                SignatureAlgorithm.HMAC_SHA256,
                new SignatureTemplate(SignatureTemplate.QUERY_STRING),
                SignatureEncoding.HEX,
                null,
                "signature",
                null,
                "timestamp");
    }

    private RequestSigner.Signed sign(SignatureSettings settings, String uri, byte[] body) {
        return signer.sign(settings, "key-1:secret-1", HttpMethod.POST, URI.create(uri), body, NOW);
    }

    @Test
    void carries_the_signature_and_the_timestamp_in_the_headers_the_api_named() {
        var signed = sign(coinbase(), "https://api.coinbase.com/v2/accounts", null);

        assertThat(signed.headers()).containsOnlyKeys("CB-ACCESS-TIMESTAMP", "CB-ACCESS-SIGN");
        assertThat(signed.headers().get("CB-ACCESS-TIMESTAMP")).isEqualTo("1700000000");
        // SHA-256 in hexadecimal, and nothing resembling the stored value.
        assertThat(signed.headers().get("CB-ACCESS-SIGN")).hasSize(64).doesNotContain("secret-1");
        assertThat(signed.uri()).hasToString("https://api.coinbase.com/v2/accounts");
    }

    @Test
    void signs_the_body_it_will_actually_send() {
        String withBody = sign(
                        coinbase(),
                        "https://api.coinbase.com/v2/orders",
                        "{\"size\":1}".getBytes(StandardCharsets.UTF_8))
                .headers()
                .get("CB-ACCESS-SIGN");
        String withOther = sign(
                        coinbase(),
                        "https://api.coinbase.com/v2/orders",
                        "{\"size\":2}".getBytes(StandardCharsets.UTF_8))
                .headers()
                .get("CB-ACCESS-SIGN");

        assertThat(withBody).isNotEqualTo(withOther);
    }

    @Test
    void signs_the_path_so_one_signature_cannot_be_replayed_against_another_resource() {
        assertThat(sign(coinbase(), "https://api.coinbase.com/v2/accounts", null)
                        .headers()
                        .get("CB-ACCESS-SIGN"))
                .isNotEqualTo(sign(coinbase(), "https://api.coinbase.com/v2/orders", null)
                        .headers()
                        .get("CB-ACCESS-SIGN"));
    }

    @Test
    void gives_the_same_answer_for_the_same_request() {
        assertThat(sign(coinbase(), "https://api.coinbase.com/v2/accounts", null)
                        .headers())
                .isEqualTo(sign(coinbase(), "https://api.coinbase.com/v2/accounts", null)
                        .headers());
    }

    /**
     * The half before the colon identifies the caller and travels in a header of its own; only the
     * half after it is a key. Signing with the whole pair would be a different signature entirely,
     * and one the upstream could not reproduce.
     */
    @Test
    void signs_with_the_secret_half_alone() {
        var settings = coinbase();
        var uri = URI.create("https://api.coinbase.com/v2/accounts");
        var mine = signer.sign(settings, "key-1:secret-1", HttpMethod.POST, uri, null, NOW);
        var renamed = signer.sign(settings, "another-key:secret-1", HttpMethod.POST, uri, null, NOW);

        assertThat(mine.headers()).isEqualTo(renamed.headers());
    }

    @Test
    void appends_the_timestamp_before_signing_and_the_signature_after_it() {
        var signed = sign(binance(), "https://api.binance.com/api/v3/order?symbol=BTCUSDT", null);

        assertThat(signed.headers()).isEmpty();
        // Order matters: what was signed is the query up to and including the timestamp, so the
        // signature has to come last or the upstream would rebuild a different string.
        assertThat(signed.uri().getRawQuery())
                .startsWith("symbol=BTCUSDT&timestamp=1700000000&signature=")
                .doesNotContain("secret-1");
    }

    @Test
    void covers_the_timestamp_it_appended() {
        // The same request signed a second later must not produce the same signature, or a captured
        // one would stay valid for as long as the upstream's clock tolerance allows.
        var settings = binance();
        var uri = URI.create("https://api.binance.com/api/v3/order?symbol=BTCUSDT");
        String first = signer.sign(settings, "k:s", HttpMethod.POST, uri, null, NOW)
                .uri()
                .getRawQuery();
        String later = signer.sign(settings, "k:s", HttpMethod.POST, uri, null, NOW.plusSeconds(1))
                .uri()
                .getRawQuery();

        assertThat(first).isNotEqualTo(later);
    }

    @Test
    void refuses_a_stored_value_that_is_not_a_pair() {
        var settings = coinbase();
        var uri = URI.create("https://api.coinbase.com/v2/accounts");

        assertThatThrownBy(() -> signer.sign(settings, "no-colon-here", HttpMethod.GET, uri, null, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key:secret");
    }

    @Test
    void changes_with_the_algorithm() {
        var sha512 = new SignatureSettings(
                SignatureAlgorithm.HMAC_SHA512,
                new SignatureTemplate(SignatureTemplate.TIMESTAMP_METHOD_PATH_BODY),
                SignatureEncoding.HEX,
                "CB-ACCESS-SIGN",
                null,
                null,
                null);

        assertThat(sign(sha512, "https://api.coinbase.com/v2/accounts", null)
                        .headers()
                        .get("CB-ACCESS-SIGN"))
                .hasSize(128);
    }

    @Test
    void writes_the_signature_the_way_the_api_reads_it() {
        var base64 = new SignatureSettings(
                SignatureAlgorithm.HMAC_SHA256,
                new SignatureTemplate("{method}{path}"),
                SignatureEncoding.BASE64,
                "X-Signature",
                null,
                null,
                null);

        // 32 bytes of SHA-256, padded: base64 makes 44 characters of them, ending in '='.
        assertThat(sign(base64, "https://api.example.com/v1/things", null)
                        .headers()
                        .get("X-Signature"))
                .hasSize(44)
                .endsWith("=");
    }
}
