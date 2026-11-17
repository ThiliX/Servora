package com.servora.usage;

import com.servora.common.security.CurrentUser;
import com.servora.membership.Permission;
import com.servora.membership.WorkspaceAccess;
import com.servora.membership.WorkspaceContext;
import com.servora.usage.UsageService.Plan;
import com.servora.usage.UsageService.Snapshot;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** The Usage &amp; Plan page: this month's usage against the workspace's plan limits. */
@RestController
public class UsageController {

    public record Used(
            long aiRequests,
            long inputTokens,
            long outputTokens,
            long tokens,
            // Null when no price is configured, so an unknown cost is never shown as zero.
            BigDecimal estimatedCostUsd,
            long conversations,
            long documents) {}

    public record SourceUsage(String source, long requests, long tokens, BigDecimal estimatedCostUsd) {}

    public record DailyUsage(LocalDate date, long aiRequests, long tokens, long conversations) {}

    public record UsageReport(
            Plan plan,
            Instant periodStart,
            Instant periodEnd,
            Used used,
            boolean costConfigured,
            List<SourceUsage> bySource,
            List<DailyUsage> daily,
            List<Plan> plans) {}

    private final WorkspaceAccess access;
    private final UsageService usage;
    private final JdbcClient jdbc;

    public UsageController(WorkspaceAccess access, UsageService usage, JdbcClient jdbc) {
        this.access = access;
        this.usage = usage;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/workspaces/{workspaceId}/usage")
    @Transactional(readOnly = true)
    public UsageReport report(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId) {
        WorkspaceContext ctx = access.authorize(CurrentUser.id(jwt), workspaceId, Permission.VIEW_USAGE);
        Snapshot snapshot = usage.snapshot(ctx.tenantId());
        boolean priced = usage.costConfigured();

        List<SourceUsage> bySource = jdbc.sql("""
                        SELECT source, count(*) AS requests, coalesce(sum(input_tokens + output_tokens), 0) AS tokens,
                               coalesce(sum(estimated_cost_micros), 0) AS cost
                        FROM usage_events
                        WHERE tenant_id = :tenantId AND event_type = 'AI_REQUEST'
                          AND occurred_at >= :start AND occurred_at < :end
                        GROUP BY source ORDER BY source
                        """)
                .param("tenantId", ctx.tenantId())
                .param("start", Timestamp.from(snapshot.periodStart()))
                .param("end", Timestamp.from(snapshot.periodEnd()))
                .query((rs, n) -> new SourceUsage(
                        rs.getString("source"),
                        rs.getLong("requests"),
                        rs.getLong("tokens"),
                        priced ? UsageService.dollars(rs.getLong("cost")) : null))
                .list();

        // Every day of the period so far, including days with no usage.
        List<DailyUsage> daily = jdbc.sql("""
                        SELECT d::date AS day,
                               count(u.id) FILTER (WHERE u.event_type = 'AI_REQUEST') AS ai_requests,
                               coalesce(sum(u.input_tokens + u.output_tokens), 0) AS tokens,
                               count(u.id) FILTER (WHERE u.event_type = 'CONVERSATION_STARTED') AS conversations
                        FROM generate_series(CAST(:first AS date), CAST(:last AS date), interval '1 day') AS d
                        LEFT JOIN usage_events u
                          ON u.tenant_id = :tenantId
                         AND u.occurred_at >= d AT TIME ZONE 'UTC'
                         AND u.occurred_at < (d + interval '1 day') AT TIME ZONE 'UTC'
                        GROUP BY d ORDER BY d
                        """)
                .param("tenantId", ctx.tenantId())
                .param("first", LocalDate.ofInstant(snapshot.periodStart(), java.time.ZoneOffset.UTC))
                .param("last", usage.today())
                .query((rs, n) -> new DailyUsage(
                        rs.getObject("day", LocalDate.class),
                        rs.getLong("ai_requests"),
                        rs.getLong("tokens"),
                        rs.getLong("conversations")))
                .list();

        Used used = new Used(
                snapshot.aiRequests(),
                snapshot.inputTokens(),
                snapshot.outputTokens(),
                snapshot.tokens(),
                priced ? UsageService.dollars(snapshot.estimatedCostMicros()) : null,
                snapshot.conversations(),
                snapshot.documents());
        return new UsageReport(
                snapshot.plan(), snapshot.periodStart(), snapshot.periodEnd(), used, priced, bySource, daily,
                usage.catalogue());
    }
}
