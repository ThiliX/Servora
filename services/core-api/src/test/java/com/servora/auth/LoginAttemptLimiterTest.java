package com.servora.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.servora.support.IntegrationTestBase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Runs against the Redis container of the integration tests. */
class LoginAttemptLimiterTest extends IntegrationTestBase {

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

    private static final int MAX = 3;

    private final TestClock testClock = new TestClock();
    private LoginAttemptLimiter limiter;
    // Redis outlives each test, so every test uses keys of its own.
    private final String key = "key-" + UUID.randomUUID();

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void createLimiter() {
        limiter = new LoginAttemptLimiter(redis, Duration.ofMinutes(15), testClock);
    }

    @Test
    void blocksOnceTheFailureLimitIsReached() {
        limiter.recordFailure(key);
        limiter.recordFailure(key);
        assertThat(limiter.isBlocked(key, MAX)).isFalse();

        limiter.recordFailure(key);

        assertThat(limiter.isBlocked(key, MAX)).isTrue();
    }

    @Test
    void keysAreIndependent() {
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(key + "-attacker");
        }

        assertThat(limiter.isBlocked(key + "-attacker", MAX)).isTrue();
        assertThat(limiter.isBlocked(key + "-someone-else", MAX)).isFalse();
    }

    @Test
    void failuresExpireAfterTheWindow() {
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(key);
        }

        testClock.advance(Duration.ofMinutes(15));

        assertThat(limiter.isBlocked(key, MAX)).isFalse();
    }

    @Test
    void theWindowSlidesSoOnlyRecentFailuresCount() {
        limiter.recordFailure(key);
        testClock.advance(Duration.ofMinutes(10));
        limiter.recordFailure(key);
        limiter.recordFailure(key);
        assertThat(limiter.isBlocked(key, MAX)).isTrue();

        testClock.advance(Duration.ofMinutes(6));

        assertThat(limiter.isBlocked(key, MAX)).isFalse();
    }

    @Test
    void resetClearsFailures() {
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(key);
        }

        limiter.reset(key);

        assertThat(limiter.isBlocked(key, MAX)).isFalse();
    }

    @Test
    void theKeyIsStoredHashedNotInClearText() {
        limiter.recordFailure("account:someone@example.test");

        assertThat(redis.keys("servora:login:*")).noneMatch(stored -> stored.contains("someone@example.test"));
        limiter.reset("account:someone@example.test");
    }
}
