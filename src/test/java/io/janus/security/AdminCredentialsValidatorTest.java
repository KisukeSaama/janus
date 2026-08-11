package io.janus.security;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AdminCredentialsValidatorTest {

    private static void validate(String password) {
        new AdminCredentialsValidator(password).afterPropertiesSet();
    }

    @Test
    void acceptsALongUnguessablePassword() {
        assertThatNoException().isThrownBy(() -> validate("7Qb!vTz2LmXe4RpA9dWf"));
    }

    @Test
    void refusesTheShippedPlaceholder() {
        assertThatThrownBy(() -> validate("change-me-in-production")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> validate("replace-with-a-long-random-value"))
                .isInstanceOf(IllegalStateException.class);
        // The `.env.example` value: it satisfies every composition rule, so only this list stops it.
        assertThatThrownBy(() -> validate("Replace-With-A-Long-Random-Value-9!"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesAnEmptyOrShortPassword() {
        assertThatThrownBy(() -> validate("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> validate(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> validate("short")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesAPasswordMissingACharacterClass() {
        assertThatThrownBy(() -> validate("lowercaseonly")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> validate("NoDigitsHere!")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> validate("n0uppercase4!")).isInstanceOf(IllegalStateException.class);
    }

    /** Eight characters are enough when lower case, upper case and digits are present. */
    @Test
    void acceptsAShortPasswordWithoutASpecialCharacter() {
        assertThatNoException().isThrownBy(() -> validate("aB3vTz91"));
    }
}
