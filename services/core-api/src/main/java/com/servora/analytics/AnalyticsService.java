package com.servora.analytics;

import com.servora.common.error.ApiException;
import com.servora.membership.Permission;
import com.servora.membership.WorkspaceAccess;
import com.servora.membership.WorkspaceContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workspace metrics for the Overview page (FR-038). Every query is scoped to the workspace twice:
 * by an explicit tenant filter and by Row-Level Security. Days are UTC calendar days.
 *
 * <p>Definitions (also in docs/api/README.md):
 * <ul>
 *   <li><b>Escalated</b>: the conversation has a ticket.
 *   <li><b>Resolved by AI</b>: no ticket, and at least one AI reply was delivered with outcome ANSWERED.
 *   <li><b>Unanswered</b>: neither of the above, for example the customer left before asking.
 * </ul>
 */
@Service
public class AnalyticsService {

    public static final Set<Integer> RANGES = Set.of(7, 30, 90);

    public record Range(LocalDate from, LocalDate to, int days) {}

    public record ConversationCounts(long total, long resolvedByAi, long escalated, long unanswered) {}

    public record Day(LocalDate date, long conversations, long escalated) {}

    public record Queue(
            long open, long inProgress, long waitingForCustomer, long unassigned, Map<String, Long> byPriority) {}

    public record Responses(
            long ticketsCreated,
            long ticketsResolved,
            Double medianFirstResponseSeconds,
            Double medianResolutionSeconds,
            Double medianAiLatencyMs) {}

    public record Count(String key, long count) {}

    public record Satisfaction(Double average, long count, Map<Integer, Long> distribution) {}

    public record Overview(
            Range range,
            ConversationCounts conversations,
            List<Day> daily,
            Queue queue,
            Responses responses,
            List<Count> intents,
            List<Count> aiOutcomes,
            List<Count> escalationReasons,
            List<Count> resolutionOutcomes,
            Satisfaction satisfaction) {}

    private final WorkspaceAccess access;
    private final JdbcClient jdbc;
    private final Clock clock;

    public AnalyticsService(WorkspaceAccess access, JdbcClient jdbc, Clock clock) {
        this.access = access;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Overview overview(UUID userId, UUID workspaceId, int days) {
        if (!RANGES.contains(days)) {
            throw ApiException.badRequest("INVALID_RANGE", "The range must be 7, 30 or 90 days.");
        }
        WorkspaceContext ctx = access.authorize(userId, workspaceId, Permission.VIEW_ANALYTICS);
        UUID tenant = ctx.tenantId();
        LocalDate to = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate from = to.minusDays(days - 1L);
        Timestamp start = Timestamp.from(from.atStartOfDay(ZoneOffset.UTC).toInstant());
        Timestamp end = Timestamp.from(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());

        ConversationCounts conversations = jdbc.sql("""
                        WITH c AS (
                            SELECT c.id,
                                   EXISTS (SELECT 1 FROM tickets t
                                           WHERE t.tenant_id = c.tenant_id AND t.conversation_id = c.id) AS escalated,
                                   EXISTS (SELECT 1 FROM messages m JOIN ai_interactions i
                                             ON i.tenant_id = m.tenant_id AND i.id = m.interaction_id
                                           WHERE m.tenant_id = c.tenant_id AND m.conversation_id = c.id
                                             AND m.sender_type = 'ASSISTANT' AND m.delivery_status = 'DELIVERED'
                                             AND i.outcome = 'ANSWERED') AS answered
                            FROM conversations c
                            WHERE c.tenant_id = :tenant AND c.created_at >= :start AND c.created_at < :end
                        )
                        SELECT count(*) AS total,
                               count(*) FILTER (WHERE NOT escalated AND answered) AS resolved_by_ai,
                               count(*) FILTER (WHERE escalated) AS escalated,
                               count(*) FILTER (WHERE NOT escalated AND NOT answered) AS unanswered
                        FROM c
                        """)
                .param("tenant", tenant)
                .param("start", start)
                .param("end", end)
                .query((rs, n) -> new ConversationCounts(
                        rs.getLong("total"), rs.getLong("resolved_by_ai"), rs.getLong("escalated"), rs.getLong("unanswered")))
                .single();

        List<Day> daily = jdbc.sql("""
                        SELECT d::date AS day,
                               count(c.id) AS conversations,
                               count(c.id) FILTER (WHERE EXISTS (
                                   SELECT 1 FROM tickets t WHERE t.tenant_id = c.tenant_id AND t.conversation_id = c.id))
                                   AS escalated
                        FROM generate_series(CAST(:from AS date), CAST(:to AS date), interval '1 day') AS d
                        LEFT JOIN conversations c
                          ON c.tenant_id = :tenant
                         AND c.created_at >= d AT TIME ZONE 'UTC'
                         AND c.created_at < (d + interval '1 day') AT TIME ZONE 'UTC'
                        GROUP BY d ORDER BY d
                        """)
                .param("tenant", tenant)
                .param("from", from)
                .param("to", to)
                .query((rs, n) -> new Day(rs.getObject("day", LocalDate.class), rs.getLong("conversations"), rs.getLong("escalated")))
                .list();

        // The queue is a snapshot of now, not of the range.
        Map<String, Object> queueRow = jdbc.sql("""
                        SELECT count(*) FILTER (WHERE status = 'OPEN') AS open,
                               count(*) FILTER (WHERE status = 'IN_PROGRESS') AS in_progress,
                               count(*) FILTER (WHERE status = 'WAITING_FOR_CUSTOMER') AS waiting,
                               count(*) FILTER (WHERE assignee_membership_id IS NULL) AS unassigned,
                               count(*) FILTER (WHERE priority = 'LOW') AS low,
                               count(*) FILTER (WHERE priority = 'NORMAL') AS normal,
                               count(*) FILTER (WHERE priority = 'HIGH') AS high,
                               count(*) FILTER (WHERE priority = 'URGENT') AS urgent
                        FROM tickets
                        WHERE tenant_id = :tenant AND status IN ('OPEN', 'IN_PROGRESS', 'WAITING_FOR_CUSTOMER')
                        """)
                .param("tenant", tenant)
                .query()
                .singleRow();
        Queue queue = new Queue(
                number(queueRow, "open"),
                number(queueRow, "in_progress"),
                number(queueRow, "waiting"),
                number(queueRow, "unassigned"),
                Map.of(
                        "LOW", number(queueRow, "low"),
                        "NORMAL", number(queueRow, "normal"),
                        "HIGH", number(queueRow, "high"),
                        "URGENT", number(queueRow, "urgent")));

        Responses responses = jdbc.sql("""
                        SELECT
                          (SELECT count(*) FROM tickets
                           WHERE tenant_id = :tenant AND created_at >= :start AND created_at < :end) AS created,
                          (SELECT count(*) FROM tickets
                           WHERE tenant_id = :tenant AND resolved_at >= :start AND resolved_at < :end) AS resolved,
                          (SELECT percentile_cont(0.5) WITHIN GROUP (
                                      ORDER BY extract(epoch FROM first_response_at - created_at))
                           FROM tickets
                           WHERE tenant_id = :tenant AND created_at >= :start AND created_at < :end
                             AND first_response_at IS NOT NULL) AS first_response,
                          (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM resolved_at - created_at))
                           FROM tickets
                           WHERE tenant_id = :tenant AND resolved_at >= :start AND resolved_at < :end) AS resolution,
                          (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY latency_ms)
                           FROM ai_interactions
                           WHERE tenant_id = :tenant AND source = 'WIDGET' AND created_at >= :start AND created_at < :end
                             AND outcome NOT IN ('OVER_LIMIT', 'SERVICE_UNAVAILABLE')) AS ai_latency
                        """)
                .param("tenant", tenant)
                .param("start", start)
                .param("end", end)
                .query((rs, n) -> new Responses(
                        rs.getLong("created"),
                        rs.getLong("resolved"),
                        rs.getObject("first_response", Double.class),
                        rs.getObject("resolution", Double.class),
                        rs.getObject("ai_latency", Double.class)))
                .single();

        List<Count> intents = counts("""
                        SELECT intent AS key, count(*) AS n FROM ai_interactions
                        WHERE tenant_id = :tenant AND source = 'WIDGET' AND intent IS NOT NULL
                          AND created_at >= :start AND created_at < :end
                        GROUP BY intent ORDER BY n DESC, intent LIMIT 10
                        """, tenant, start, end);
        List<Count> aiOutcomes = counts("""
                        SELECT outcome AS key, count(*) AS n FROM ai_interactions
                        WHERE tenant_id = :tenant AND source = 'WIDGET' AND created_at >= :start AND created_at < :end
                        GROUP BY outcome ORDER BY n DESC, outcome
                        """, tenant, start, end);
        List<Count> reasons = counts("""
                        SELECT reason AS key, count(*) AS n FROM tickets
                        WHERE tenant_id = :tenant AND created_at >= :start AND created_at < :end
                        GROUP BY reason ORDER BY n DESC, reason
                        """, tenant, start, end);
        List<Count> outcomes = counts("""
                        SELECT resolution_outcome AS key, count(*) AS n FROM tickets
                        WHERE tenant_id = :tenant AND resolved_at >= :start AND resolved_at < :end
                          AND resolution_outcome IS NOT NULL
                        GROUP BY resolution_outcome ORDER BY n DESC, resolution_outcome
                        """, tenant, start, end);

        Map<String, Object> ratingRow = jdbc.sql("""
                        SELECT avg(rating)::float8 AS average, count(*) AS n,
                               count(*) FILTER (WHERE rating = 1) AS r1, count(*) FILTER (WHERE rating = 2) AS r2,
                               count(*) FILTER (WHERE rating = 3) AS r3, count(*) FILTER (WHERE rating = 4) AS r4,
                               count(*) FILTER (WHERE rating = 5) AS r5
                        FROM conversation_feedback
                        WHERE tenant_id = :tenant AND created_at >= :start AND created_at < :end
                        """)
                .param("tenant", tenant)
                .param("start", start)
                .param("end", end)
                .query()
                .singleRow();
        Satisfaction satisfaction = new Satisfaction(
                ratingRow.get("average") == null ? null : ((Number) ratingRow.get("average")).doubleValue(),
                number(ratingRow, "n"),
                Map.of(1, number(ratingRow, "r1"), 2, number(ratingRow, "r2"), 3, number(ratingRow, "r3"),
                        4, number(ratingRow, "r4"), 5, number(ratingRow, "r5")));

        return new Overview(
                new Range(from, to, days), conversations, daily, queue, responses, intents, aiOutcomes, reasons, outcomes,
                satisfaction);
    }

    private List<Count> counts(String sql, UUID tenant, Timestamp start, Timestamp end) {
        return jdbc.sql(sql)
                .param("tenant", tenant)
                .param("start", start)
                .param("end", end)
                .query((rs, n) -> new Count(rs.getString("key"), rs.getLong("n")))
                .list();
    }

    private static long number(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value == null ? 0 : ((Number) value).longValue();
    }
}
