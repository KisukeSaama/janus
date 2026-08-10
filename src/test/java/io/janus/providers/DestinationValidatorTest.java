package io.janus.providers;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DestinationValidatorTest {
    @Test void rejectsLocalAndNonHttpsDestinations(){var validator=new DestinationValidator(false);assertThatThrownBy(()->validator.validate("http://example.com")).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->validator.validate("https://127.0.0.1")).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->validator.validate("https://user@example.com")).isInstanceOf(IllegalArgumentException.class);}
    @Test void permitsExplicitPrivateDestinationsOnlyInOptInMode(){var validator=new DestinationValidator(true);assertThat(validator.validate("http://localhost:9000").getHost()).isEqualTo("localhost");}
}
