package io.janus.security;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AdminCredentialsValidatorTest {

    private static void validate(String username, String password) {
        new AdminCredentialsValidator(username, password).afterPropertiesSet();
    }

    @Test
    void acceptsALongUnguessablePassword() {
        assertThatNoException().isThrownBy(() -> validate("admin", "7Qb!vTz2LmXe4RpA9dWf"));
    }

    @Test
    void refusesTheShippedPlaceholder() {
        assertThatThrownBy(() -> validate("admin", "change-me-in-production"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> validate("admin", "replace-with-a-long-random-value"))
                .isInstanceOf(IllegalStateException.class);
        // The `.env.example` value: it satisfies every composition rule, so only this list stops it.
        assertThatThrownBy(() -> validate("admin", "Replace-With-A-Long-Random-Value-9!"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesAnEmptyOrShortPassword() {
        assertThatThrownBy(() -> validate("admin", "")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> validate("admin", null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> validate("admin", "short")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesAPasswordMissingACharacterClass() {
        assertThatThrownBy(() -> validate("admin", "lowercaseonly")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> validate("admin", "NoDigitsHere!")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> validate("admin", "N0Specials4Me")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> validate("admin", "n0uppercase4!")).isInstanceOf(IllegalStateException.class);
    }

    /** The length concession is paid for by variety: eight characters are enough with all four. */
    @Test
    void acceptsAShortPasswordSpanningEveryClass() {
        assertThatNoException().isThrownBy(() -> validate("admin", "aB3!vTz9"));
    }

    @Test
    void refusesAPasswordEqualToTheUsername() {
        assertThatThrownBy(() -> validate("averylongadminname", "averylongadminname"))
                .isInstanceOf(IllegalStateException.class);
    }
}
