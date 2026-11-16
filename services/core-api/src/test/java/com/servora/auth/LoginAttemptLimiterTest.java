package com.servora.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class LoginAttemptLimiterTest {

    private static final class TestClock extends Clock {

        private Instant now = Instant.parse("2026-11-16T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final TestClock clock = new TestClock();
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(3, Duration.ofMinutes(15), clock);

    @Test
    void blocksOnceTheFailureLimitIsReached() {
        limiter.recordFailure("key");
        limiter.recordFailure("key");
        assertThat(limiter.isBlocked("key")).isFalse();

        limiter.recordFailure("key");

        assertThat(limiter.isBlocked("key")).isTrue();
    }

    @Test
    void keysAreIndependent() {
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure("attacker");
        }

        assertThat(limiter.isBlocked("attacker")).isTrue();
        assertThat(limiter.isBlocked("someone-else")).isFalse();
    }

    @Test
    void failuresExpireAfterTheWindow() {
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure("key");
        }

        clock.advance(Duration.ofMinutes(15));

        assertThat(limiter.isBlocked("key")).isFalse();
    }

    @Test
    void theWindowSlidesSoOnlyRecentFailuresCount() {
        limiter.recordFailure("key");
        clock.advance(Duration.ofMinutes(10));
        limiter.recordFailure("key");
        limiter.recordFailure("key");
        assertThat(limiter.isBlocked("key")).isTrue();

        clock.advance(Duration.ofMinutes(6));

        assertThat(limiter.isBlocked("key")).isFalse();
    }

    @Test
    void resetClearsFailures() {
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure("key");
        }

        limiter.reset("key");

        assertThat(limiter.isBlocked("key")).isFalse();
    }
}
