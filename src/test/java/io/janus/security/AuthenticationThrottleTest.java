package io.janus.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuthenticationThrottleTest {

    @Test
    void blocksOnlyAfterTheConfiguredNumberOfFailures() {
        var throttle = new AuthenticationThrottle(3, 300, 900);
        throttle.recordFailure("client");
        throttle.recordFailure("client");
        assertThat(throttle.isBlocked("client")).isFalse();
        throttle.recordFailure("client");
        assertThat(throttle.isBlocked("client")).isTrue();
    }

    /**
     * A block lasts a quarter of an hour by default. Without this the refusal carried no
     * {@code Retry-After}, so a caller could not tell a block from a wrong key.
     */
    @Test
    void aBlockedClientCanBeToldHowLongToWait() {
        var throttle = new AuthenticationThrottle(1, 300, 900);
        assertThat(throttle.blockedForSeconds("client")).isZero();

        throttle.recordFailure("client");

        assertThat(throttle.blockedForSeconds("client")).isBetween(890L, 901L);
        assertThat(throttle.blockedForSeconds("bystander")).isZero();
    }

    @Test
    void oneClientCannotBlockAnother() {
        var throttle = new AuthenticationThrottle(1, 300, 900);
        throttle.recordFailure("attacker");
        assertThat(throttle.isBlocked("attacker")).isTrue();
        assertThat(throttle.isBlocked("bystander")).isFalse();
    }

    @Test
    void aSuccessfulAttemptClearsTheCounter() {
        var throttle = new AuthenticationThrottle(2, 300, 900);
        throttle.recordFailure("client");
        throttle.recordSuccess("client");
        throttle.recordFailure("client");
        assertThat(throttle.isBlocked("client")).isFalse();
    }

    @Test
    void failuresSpreadBeyondTheWindowDoNotAccumulate() throws InterruptedException {
        var throttle = new AuthenticationThrottle(2, 0, 900);
        throttle.recordFailure("client");
        Thread.sleep(2);
        throttle.recordFailure("client");
        assertThat(throttle.isBlocked("client")).isFalse();
    }

    @Test
    void aBlockExpires() throws InterruptedException {
        var throttle = new AuthenticationThrottle(1, 300, 0);
        throttle.recordFailure("client");
        Thread.sleep(2);
        assertThat(throttle.isBlocked("client")).isFalse();
    }
}
