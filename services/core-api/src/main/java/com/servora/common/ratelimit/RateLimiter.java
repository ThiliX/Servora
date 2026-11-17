package com.servora.common.ratelimit;

import com.servora.common.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Fixed-window counters in Redis, shared by every API instance.
 *
 * <p>Each bucket counts requests in the current window; the window is derived from the
 * application clock, so tests can move time forward. If Redis is unreachable the limiter fails
 * closed: limited operations are refused rather than left unbounded.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private static final String PREFIX = "servora:rl:";

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RateLimiter(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /** Counts one request against {@code bucket} and returns whether it is within {@code limit}. */
    public boolean tryAcquire(String bucket, int limit, Duration window) {
        long windowSeconds = Math.max(1, window.toSeconds());
        long index = clock.instant().getEpochSecond() / windowSeconds;
        String key = PREFIX + bucket + ":" + windowSeconds + ":" + index;
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1) {
                redis.expire(key, Duration.ofSeconds(windowSeconds + 5));
            }
            return count != null && count <= limit;
        } catch (DataAccessException e) {
            log.warn("Rate limiter unavailable: {}", e.getClass().getSimpleName());
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "The service is busy. Try again shortly.");
        }
    }

    /** A stable, non-reversible bucket name for a client address, so raw IP addresses are never stored. */
    public static String hashed(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
