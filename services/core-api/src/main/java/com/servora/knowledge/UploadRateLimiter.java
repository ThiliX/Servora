package com.servora.knowledge;

import com.servora.common.ratelimit.RateLimiter;
import com.servora.knowledge.KnowledgeConfig.KnowledgeProperties;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Caps uploads per workspace per hour. Uploads trigger parsing and embedding, so they are an
 * expensive operation worth bounding. Counted in Redis, so the cap holds across API instances.
 */
@Component
public class UploadRateLimiter {

    private static final Duration WINDOW = Duration.ofHours(1);

    private final RateLimiter limiter;
    private final int limit;

    public UploadRateLimiter(RateLimiter limiter, KnowledgeProperties properties) {
        this.limiter = limiter;
        this.limit = properties.uploadsPerHour();
    }

    /** Records an upload attempt and returns whether it is within the limit. */
    public boolean tryAcquire(UUID tenantId) {
        return limiter.tryAcquire("upload:" + tenantId, limit, WINDOW);
    }
}
