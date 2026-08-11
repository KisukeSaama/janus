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
    }

    @Test
    void refusesAnEmptyOrShortPassword() {
        assertThatThrownBy(() -> validate("admin", "")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> validate("admin", null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> validate("admin", "short-password")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesAPasswordEqualToTheUsername() {
        assertThatThrownBy(() -> validate("averylongadminname", "averylongadminname"))
                .isInstanceOf(IllegalStateException.class);
    }
}
