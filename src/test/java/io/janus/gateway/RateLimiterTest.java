package io.janus.gateway;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RateLimiterTest {
    private final RateLimiter limiter = new RateLimiter();

    @Test
    void anUnsetAllowanceNeverRefuses() {
        for (int i = 0; i < 100; i++)
            assertTrue(limiter.tryAcquire("open", 0, 0).allowed());
        assertFalse(limiter.tryAcquire("open", 0, 0).measured());
    }

    @Test
    void spendsTheBurstThenRefuses() {
        for (int i = 0; i < 3; i++) assertTrue(limiter.tryAcquire("k", 60, 3).allowed(), "permit " + i);
        var refused = limiter.tryAcquire("k", 60, 3);
        assertFalse(refused.allowed());
        assertEquals(60, refused.limit());
        assertEquals(0, refused.remaining());
        assertTrue(refused.retryAfterSeconds() >= 1);
    }

    @Test
    void burstDefaultsToATenthOfTheAllowance() {
        assertEquals(6, RateLimiter.capacity(60, 0));
        assertEquals(1, RateLimiter.capacity(5, 0));
        assertEquals(2, RateLimiter.capacity(60, 2));
    }

    @Test
    void refillsWithTime() throws InterruptedException {
        // 6000 a minute is one every ten milliseconds, so a short sleep must return a permit.
        assertTrue(limiter.tryAcquire("fast", 6000, 1).allowed());
        assertFalse(limiter.tryAcquire("fast", 6000, 1).allowed());
        Thread.sleep(60);
        assertTrue(limiter.tryAcquire("fast", 6000, 1).allowed());
    }

    @Test
    void waitingIsBoundedByTheBudget() {
        assertTrue(limiter.acquire("slow", 60, 1, 200).allowed());
        // The next permit is a second away; a caller willing to wait 10ms must be refused.
        assertFalse(limiter.acquire("slow", 60, 1, 10).allowed());
    }

    @Test
    void waitingSucceedsWhenThePermitArrivesInTime() {
        assertTrue(limiter.acquire("waited", 6000, 1, 500).allowed());
        assertTrue(limiter.acquire("waited", 6000, 1, 500).allowed());
    }

    @Test
    void aReducedBurstAppliesImmediately() {
        for (int i = 0; i < 10; i++) limiter.tryAcquire("shrink", 600, 10);
        // The bucket was sized for ten; asking for two must not hand back the old capacity.
        assertFalse(limiter.tryAcquire("shrink", 600, 2).allowed());
    }

    @Test
    void forgettingRestoresAFullBucket() {
        assertTrue(limiter.tryAcquire("gone", 60, 1).allowed());
        assertFalse(limiter.tryAcquire("gone", 60, 1).allowed());
        limiter.forget("gone");
        assertTrue(limiter.tryAcquire("gone", 60, 1).allowed());
    }

    @Test
    void keysDoNotShareAnAllowance() {
        assertTrue(limiter.tryAcquire("one", 60, 1).allowed());
        assertTrue(limiter.tryAcquire("two", 60, 1).allowed());
    }
}
