package io.janus.credentials;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * The rules a signing recipe has to satisfy before anything is stored.
 *
 * <p>All of them are caught here rather than upstream, because upstream catches them as a refusal
 * with no explanation. The one that matters most is the destination: a signature sent nowhere leaves
 * a request looking complete and failing for reasons no log would show.
 */
class SignatureSettingsTest {

    private static SignatureSettings with(
            String signatureHeader, String signatureParameter, String timestampHeader, String timestampParameter) {
        return new SignatureSettings(
                SignatureAlgorithm.HMAC_SHA256,
                new SignatureTemplate("{method}{path}"),
                SignatureEncoding.HEX,
                signatureHeader,
                signatureParameter,
                timestampHeader,
                timestampParameter);
    }

    @Test
    void accepts_a_signature_in_a_header() {
        assertThatCode(() -> with("X-Signature", null, "X-Timestamp", null).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void accepts_a_signature_in_the_query() {
        assertThatCode(() -> with(null, "signature", null, "timestamp").validate())
                .doesNotThrowAnyException();
    }

    @Test
    void accepts_a_recipe_that_needs_no_timestamp_anywhere() {
        assertThatCode(() -> with("X-Signature", null, null, null).validate()).doesNotThrowAnyException();
    }

    @Test
    void refuses_a_signature_with_nowhere_to_go() {
        assertThatThrownBy(() -> with(null, null, null, null).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void refuses_a_signature_sent_twice() {
        assertThatThrownBy(() -> with("X-Signature", "signature", null, null).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void refuses_a_timestamp_in_two_places() {
        assertThatThrownBy(() ->
                        with("X-Signature", null, "X-Timestamp", "timestamp").validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one place");
    }

    @Test
    void refuses_names_that_would_not_survive_the_wire() {
        assertThatThrownBy(() -> with("X Signature", null, null, null).validate())
                .hasMessageContaining("header name is required for the signature");
        assertThatThrownBy(() -> with(null, "sig nature", null, null).validate())
                .hasMessageContaining("query parameter name is required for the signature");
        assertThatThrownBy(() -> with("X-Signature", null, "X Timestamp", null).validate())
                .hasMessageContaining("header name is required for the timestamp");
        assertThatThrownBy(() -> with(null, "signature", null, "time stamp").validate())
                .hasMessageContaining("query parameter name is required for the timestamp");
    }

    @Test
    void refuses_a_recipe_with_nothing_to_sign_or_nothing_to_sign_it_with() {
        assertThatThrownBy(() -> new SignatureSettings(
                                null, new SignatureTemplate("{path}"), SignatureEncoding.HEX, "X-Sig", null, null, null)
                        .validate())
                .hasMessageContaining("algorithm is required");
        assertThatThrownBy(() -> new SignatureSettings(
                                SignatureAlgorithm.HMAC_SHA256, null, SignatureEncoding.HEX, "X-Sig", null, null, null)
                        .validate())
                .hasMessageContaining("recipe is required");
    }

    /** Blank is how an untouched form field arrives, and it means "not stated" rather than "empty". */
    @Test
    void treats_a_blank_field_as_absent() {
        var settings = with("X-Signature", "   ", "  ", null);

        assertThat(settings.signatureParameter()).isNull();
        assertThat(settings.timestampHeader()).isNull();
        assertThat(settings.signatureInQuery()).isFalse();
        assertThat(settings.timestampInQuery()).isFalse();
        assertThatCode(settings::validate).doesNotThrowAnyException();
    }

    /** Hexadecimal is what most APIs read, so an unstated encoding is that rather than a refusal. */
    @Test
    void defaults_the_encoding_rather_than_refusing_one_that_was_not_stated() {
        var settings = new SignatureSettings(
                SignatureAlgorithm.HMAC_SHA256, new SignatureTemplate("{path}"), null, null, "signature", null, null);

        assertThat(settings.encoding()).isEqualTo(SignatureEncoding.HEX);
        assertThat(settings.signatureInQuery()).isTrue();
    }
}
