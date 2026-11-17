package com.servora.ai;

import com.servora.ai.AiClient.AiProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Caps assistant test questions per workspace per hour: each one can be a paid model call.
 *
 * <p>State is in memory, like the login and upload limiters, until the shared store arrives in M4.
 */
@Component
public class PlaygroundRateLimiter {

    private static final Duration WINDOW = Duration.ofHours(1);

    private final int limit;
    private final Clock clock;
    private final Map<UUID, Deque<Instant>> recentByTenant = new ConcurrentHashMap<>();

    public PlaygroundRateLimiter(AiProperties properties, Clock clock) {
        this.limit = properties.testsPerHour();
        this.clock = clock;
    }

    public boolean tryAcquire(UUID tenantId) {
        Deque<Instant> recent = recentByTenant.computeIfAbsent(tenantId, id -> new ArrayDeque<>());
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
