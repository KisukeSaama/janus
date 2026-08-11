package io.janus.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class AdminPasswordEncoderTest {
    private static final String PASSWORD = "a-long-enough-administrator-password";

    private final PasswordEncoder delegate = spy(new BCryptPasswordEncoder(4));
    private final String hash = delegate.encode(PASSWORD);
    private final AdminPasswordEncoder encoder = new AdminPasswordEncoder(delegate);

    @Test
    void theSecondComparisonOfTheSamePasswordCostsNoHashing() {
        assertThat(encoder.matches(PASSWORD, hash)).isTrue();
        clearInvocations(delegate);

        for (int i = 0; i < 10; i++) assertThat(encoder.matches(PASSWORD, hash)).isTrue();

        verify(delegate, never()).matches(any(), any());
    }

    /** Guessing must not be able to fill the map, so only what verified is ever remembered. */
    @Test
    void aWrongPasswordIsNeverRemembered() {
        for (int i = 0; i < 5; i++) assertThat(encoder.matches("wrong", hash)).isFalse();

        verify(delegate, times(5)).matches("wrong", hash);
    }

    /** An entry is pinned to the hash it was checked against, so a rotated hash cannot reuse it. */
    @Test
    void anEntryDoesNotSurviveTheHashItWasVerifiedAgainst() {
        assertThat(encoder.matches(PASSWORD, hash)).isTrue();
        clearInvocations(delegate);

        String rotated = delegate.encode("a-completely-different-password");
        assertThat(encoder.matches(PASSWORD, rotated)).isFalse();
        verify(delegate).matches(PASSWORD, rotated);
    }

    /** Two passwords sharing a cached hash must not be confused for one another. */
    @Test
    void aDifferentPasswordAgainstTheSameHashIsStillChecked() {
        assertThat(encoder.matches(PASSWORD, hash)).isTrue();
        assertThat(encoder.matches(PASSWORD + "x", hash)).isFalse();
        assertThat(encoder.matches("", hash)).isFalse();
    }

    @Test
    void clearingForcesTheNextComparisonBackThroughTheDelegate() {
        assertThat(encoder.matches(PASSWORD, hash)).isTrue();
        encoder.clear();
        clearInvocations(delegate);

        assertThat(encoder.matches(PASSWORD, hash)).isTrue();
        verify(delegate).matches(PASSWORD, hash);
    }

    @Test
    void encodingIsAlwaysDelegated() {
        String encoded = encoder.encode(PASSWORD);
        assertThat(encoded).isNotEqualTo(hash);
        assertThat(delegate.matches(PASSWORD, encoded)).isTrue();
    }
}
