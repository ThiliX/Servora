package com.servora.auth;

import com.servora.common.error.ApiException;
import com.servora.common.ratelimit.RateLimiter;
import com.servora.common.security.AuthProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Counts failed sign-in attempts per key within a sliding window, in Redis, so the limit is shared
 * by every API instance and survives restarts.
 *
 * <p>Each key holds a sorted set of failure times; entries older than the window are dropped
 * before counting. Keys are hashed, so no email address or IP address is stored in Redis. If Redis
 * is unreachable, sign-in is refused rather than left unlimited, like the other rate limits.
 */
@Component
public class LoginAttemptLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptLimiter.class);
    private static final String PREFIX = "servora:login:";

    private final StringRedisTemplate redis;
    private final Duration window;
    private final Clock clock;

    @Autowired
    public LoginAttemptLimiter(StringRedisTemplate redis, AuthProperties properties, Clock clock) {
        this(redis, properties.loginFailureWindow(), clock);
    }

    LoginAttemptLimiter(StringRedisTemplate redis, Duration window, Clock clock) {
        this.redis = redis;
        this.window = window;
        this.clock = clock;
    }

    /** Whether {@code key} has at least {@code maxFailures} failures within the window. */
    public boolean isBlocked(String key, int maxFailures) {
        String redisKey = redisKey(key);
        try {
            prune(redisKey);
            Long count = redis.opsForZSet().zCard(redisKey);
            return count != null && count >= maxFailures;
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    public void recordFailure(String key) {
        String redisKey = redisKey(key);
        try {
            prune(redisKey);
            redis.opsForZSet().add(redisKey, UUID.randomUUID().toString(), clock.millis());
            redis.expire(redisKey, window.plusSeconds(5));
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    public void reset(String key) {
        try {
            redis.delete(redisKey(key));
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    private void prune(String redisKey) {
        // A failure exactly one window old no longer counts.
        redis.opsForZSet().removeRangeByScore(redisKey, Double.NEGATIVE_INFINITY, clock.millis() - window.toMillis());
    }

    private static String redisKey(String key) {
        return PREFIX + RateLimiter.hashed(key);
    }

    private static ApiException unavailable(DataAccessException e) {
        log.warn("Login limiter unavailable: {}", e.getClass().getSimpleName());
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "The service is busy. Try again shortly.");
    }
}
