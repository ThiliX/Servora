package com.servora.auth;

import com.servora.common.security.AuthProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Limits failed login attempts per key within a sliding window.
 *
 * <p>State is held in memory, so the limit applies per application instance and resets on restart.
 * That is sufficient for the single-instance M1 deployment; a shared store replaces it when the
 * public rate limits are built in M4.
 */
@Component
public class LoginAttemptLimiter {

    private static final int MAX_TRACKED_KEYS = 100_000;

    private final int maxFailures;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    @Autowired
    public LoginAttemptLimiter(AuthProperties properties, Clock clock) {
        this(properties.loginMaxFailures(), properties.loginFailureWindow(), clock);
    }

    LoginAttemptLimiter(int maxFailures, Duration window, Clock clock) {
        this.maxFailures = maxFailures;
        this.window = window;
        this.clock = clock;
    }

    public boolean isBlocked(String key) {
        Deque<Instant> attempts = failures.get(key);
        if (attempts == null) {
            return false;
        }
        synchronized (attempts) {
            prune(attempts);
            return attempts.size() >= maxFailures;
        }
    }

    public void recordFailure(String key) {
        if (failures.size() >= MAX_TRACKED_KEYS) {
            evictExpired();
        }
        Deque<Instant> attempts = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (attempts) {
            prune(attempts);
            attempts.addLast(clock.instant());
        }
    }

    public void reset(String key) {
        failures.remove(key);
    }

    private void prune(Deque<Instant> attempts) {
        Instant cutoff = clock.instant().minus(window);
        while (!attempts.isEmpty() && !attempts.peekFirst().isAfter(cutoff)) {
            attempts.removeFirst();
        }
    }

    private void evictExpired() {
        failures.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                prune(entry.getValue());
                return entry.getValue().isEmpty();
            }
        });
    }
}
