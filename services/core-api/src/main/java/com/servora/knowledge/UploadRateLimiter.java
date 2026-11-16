package com.servora.knowledge;

import com.servora.knowledge.KnowledgeConfig.KnowledgeProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Caps uploads per workspace per hour. Uploads trigger parsing and embedding, so they are an
 * expensive operation worth bounding.
 *
 * <p>State is in memory: the cap applies per API instance and resets on restart, like the login
 * limiter. A shared store replaces both when the public rate limits are built in M4.
 */
@Component
public class UploadRateLimiter {

    private static final Duration WINDOW = Duration.ofHours(1);

    private final int limit;
    private final Clock clock;
    private final Map<UUID, Deque<Instant>> uploads = new ConcurrentHashMap<>();

    @Autowired
    public UploadRateLimiter(KnowledgeProperties properties, Clock clock) {
        this(properties.uploadsPerHour(), clock);
    }

    UploadRateLimiter(int limit, Clock clock) {
        this.limit = limit;
        this.clock = clock;
    }

    /** Records an upload attempt and returns whether it is within the limit. */
    public boolean tryAcquire(UUID tenantId) {
        Deque<Instant> recent = uploads.computeIfAbsent(tenantId, id -> new ArrayDeque<>());
        synchronized (recent) {
            Instant now = clock.instant();
            Instant cutoff = now.minus(WINDOW);
            while (!recent.isEmpty() && !recent.peekFirst().isAfter(cutoff)) {
                recent.removeFirst();
            }
            if (recent.size() >= limit) {
                return false;
            }
            recent.addLast(now);
            return true;
        }
    }
}
