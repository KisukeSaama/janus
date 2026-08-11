package io.janus.credentials;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * The recipe itself, which is the one part of a signed request an operator writes by hand.
 *
 * <p>A wrong recipe fails upstream with a status and no explanation, so what can be caught here is
 * caught here: a placeholder nobody understands is refused at the form rather than discovered by a
 * caller.
 */
class SignatureTemplateTest {

    @Test
    void replaces_each_placeholder_with_what_the_request_carries() {
        var template = new SignatureTemplate("{timestamp}{method}{path}{body}");

        assertThat(template.expand(new SignatureTemplate.Parts("GET", "/v2/accounts", "", "", 1_700_000_000L)))
                .isEqualTo("1700000000GET/v2/accounts");
    }

    @Test
    void copies_through_whatever_separators_the_api_wanted() {
        var template = new SignatureTemplate("{method}\n{path}\n{query}");

        assertThat(template.expand(new SignatureTemplate.Parts("POST", "/order", "a=1&b=2", "", 0)))
                .isEqualTo("POST\n/order\na=1&b=2");
    }

    @Test
    void counts_in_milliseconds_only_when_the_recipe_asked_for_it() {
        assertThat(new SignatureTemplate("{timestamp}{path}").millis()).isFalse();
        assertThat(new SignatureTemplate(SignatureTemplate.TIMESTAMP_MILLIS + "{path}").millis())
                .isTrue();
    }

    @Test
    void refuses_a_placeholder_nothing_would_ever_fill_in() {
        assertThatThrownBy(() -> new SignatureTemplate("{nonce}{path}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("{nonce}");
    }

    @Test
    void refuses_an_empty_recipe() {
        assertThatThrownBy(() -> new SignatureTemplate("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SignatureTemplate(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refuses_one_longer_than_the_column_holding_it() {
        assertThatThrownBy(() -> new SignatureTemplate("x".repeat(SignatureTemplate.MAX_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A dollar or a backslash in the pattern is a literal, not a substitution the regex engine owns. */
    @Test
    void treats_a_literal_as_a_literal() {
        assertThat(new SignatureTemplate("$1{method}").expand(new SignatureTemplate.Parts("GET", "/", "", "", 0)))
                .isEqualTo("$1GET");
    }
}
