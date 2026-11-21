package com.servora.usage;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import com.servora.common.error.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Usage metering and plan limits (FR-038, FR-043). Events are appended in the same transaction as
 * the work they measure. Limits are read from the workspace's plan and counted over the current
 * calendar month in UTC.
 *
 * <p>Limits are checked before the work starts and counted when it is recorded, so requests that
 * run at the same moment can pass the check together. The overshoot is bounded by the number of
 * concurrent requests (ADR-019).
 *
 * <p>Every method needs a transaction with the workspace bound for Row-Level Security.
 */
@Service
public class UsageService {

    public static final String LIMIT_CODE = "PLAN_LIMIT_REACHED";

    public enum Limit {
        AI_REQUESTS,
        TOKENS,
        CONVERSATIONS,
        DOCUMENTS
    }

    public record Plan(
            String code, String name, int monthlyAiRequests, long monthlyTokens, int monthlyConversations, int maxDocuments) {}

    /** Usage in the current period, next to the plan's limits. */
    public record Snapshot(
            Plan plan,
            Instant periodStart,
            Instant periodEnd,
            long aiRequests,
            long inputTokens,
            long outputTokens,
            long estimatedCostMicros,
            long conversations,
            long documents) {

        public long tokens() {
            return inputTokens + outputTokens;
        }

        /** The first limit that is used up, if any, for AI requests. */
        public Limit exhaustedAiLimit() {
            if (aiRequests >= plan.monthlyAiRequests()) {
                return Limit.AI_REQUESTS;
            }
            if (tokens() >= plan.monthlyTokens()) {
                return Limit.TOKENS;
            }
            return null;
        }
    }

    private final JdbcClient jdbc;
    private final UsageProperties properties;
    private final Clock clock;
    private final MeterRegistry meters;

    public UsageService(JdbcClient jdbc, UsageProperties properties, Clock clock, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
        this.meters = meters;
    }

    /** Counts a request refused by a plan limit. The workspace is not a label: see the usage page. */
    private void limitHit(String limit) {
        Counter.builder("servora.plan.limit.reached")
                .description("Requests refused because a workspace reached a plan limit")
                .tag("limit", limit)
                .register(meters)
                .increment();
    }

    private static String aiLimitName(Limit limit) {
        return limit == Limit.TOKENS ? "ai_tokens" : "ai_requests";
    }

    /** Every new workspace starts on the Free plan. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createSubscription(UUID tenantId) {
        jdbc.sql("INSERT INTO subscriptions (id, tenant_id, plan_code) VALUES (:id, :tenantId, 'FREE')")
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .update();
    }

    /** Records one AI request with its tokens and estimated cost. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAiRequest(
            UUID tenantId, String source, UUID interactionId, String provider, String model, int inputTokens, int outputTokens) {
        insert(tenantId, "AI_REQUEST", source, interactionId, provider, model, inputTokens, outputTokens,
                properties.costMicros(inputTokens, outputTokens));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordDocumentUploaded(UUID tenantId, UUID documentId) {
        insert(tenantId, "DOCUMENT_UPLOADED", null, documentId, null, null, 0, 0, 0);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordConversationStarted(UUID tenantId, UUID conversationId) {
        insert(tenantId, "CONVERSATION_STARTED", null, conversationId, null, null, 0, 0, 0);
    }

    /** Whether the workspace may make another AI request this month. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean aiAllowed(UUID tenantId) {
        Limit exhausted = snapshot(tenantId).exhaustedAiLimit();
        if (exhausted != null) {
            limitHit(aiLimitName(exhausted));
        }
        return exhausted == null;
    }

    /** Throws {@link #LIMIT_CODE} if the workspace has used its AI requests or tokens for this month. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireAi(UUID tenantId) {
        Limit exhausted = snapshot(tenantId).exhaustedAiLimit();
        if (exhausted != null) {
            limitHit(aiLimitName(exhausted));
            throw limitReached(exhausted == Limit.TOKENS
                    ? "This workspace has used its AI tokens for this month."
                    : "This workspace has used its AI requests for this month.");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean conversationAllowed(UUID tenantId) {
        Snapshot snapshot = snapshot(tenantId);
        boolean allowed = snapshot.conversations() < snapshot.plan().monthlyConversations();
        if (!allowed) {
            limitHit("conversations");
        }
        return allowed;
    }

    /** Throws {@link #LIMIT_CODE} if the workspace already holds as many documents as its plan allows. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireDocumentSlot(UUID tenantId, long documentsHeld) {
        if (documentsHeld >= plan(tenantId).maxDocuments()) {
            limitHit("documents");
            throw limitReached("This workspace has reached its plan's document limit. Delete a document or change plan.");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Plan plan(UUID tenantId) {
        return jdbc.sql("""
                        SELECT p.code, p.name, p.monthly_ai_requests, p.monthly_tokens, p.monthly_conversations,
                               p.max_documents
                        FROM subscriptions s JOIN plans p ON p.code = s.plan_code
                        WHERE s.tenant_id = :tenantId
                        """)
                .param("tenantId", tenantId)
                .query((rs, n) -> new Plan(
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getInt("monthly_ai_requests"),
                        rs.getLong("monthly_tokens"),
                        rs.getInt("monthly_conversations"),
                        rs.getInt("max_documents")))
                .optional()
                .orElseThrow(() -> new IllegalStateException("Workspace has no subscription: " + tenantId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<Plan> catalogue() {
        return jdbc.sql("""
                        SELECT code, name, monthly_ai_requests, monthly_tokens, monthly_conversations, max_documents
                        FROM plans ORDER BY sort_order
                        """)
                .query((rs, n) -> new Plan(
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getInt("monthly_ai_requests"),
                        rs.getLong("monthly_tokens"),
                        rs.getInt("monthly_conversations"),
                        rs.getInt("max_documents")))
                .list();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Snapshot snapshot(UUID tenantId) {
        Plan plan = plan(tenantId);
        Instant start = periodStart();
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE event_type = 'AI_REQUEST') AS ai_requests,
                               coalesce(sum(input_tokens), 0) AS input_tokens,
                               coalesce(sum(output_tokens), 0) AS output_tokens,
                               coalesce(sum(estimated_cost_micros), 0) AS cost,
                               count(*) FILTER (WHERE event_type = 'CONVERSATION_STARTED') AS conversations,
                               (SELECT count(*) FROM documents d
                                WHERE d.tenant_id = :tenantId AND d.status <> 'DELETED') AS documents
                        FROM usage_events
                        WHERE tenant_id = :tenantId AND occurred_at >= :start AND occurred_at < :end
                        """)
                .param("tenantId", tenantId)
                .param("start", Timestamp.from(start))
                .param("end", Timestamp.from(periodEnd()))
                .query((rs, n) -> new Snapshot(
                        plan,
                        start,
                        periodEnd(),
                        rs.getLong("ai_requests"),
                        rs.getLong("input_tokens"),
                        rs.getLong("output_tokens"),
                        rs.getLong("cost"),
                        rs.getLong("conversations"),
                        rs.getLong("documents")))
                .single();
    }

    public boolean costConfigured() {
        return properties.costConfigured();
    }

    public Instant periodStart() {
        return YearMonth.now(clock.withZone(ZoneOffset.UTC)).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public Instant periodEnd() {
        return YearMonth.now(clock.withZone(ZoneOffset.UTC)).plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    /** Dollars, for display. */
    public static BigDecimal dollars(long micros) {
        return BigDecimal.valueOf(micros).movePointLeft(6).setScale(4, RoundingMode.HALF_UP);
    }

    public static ApiException limitReached(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, LIMIT_CODE, message);
    }

    private void insert(
            UUID tenantId, String type, String source, UUID referenceId, String provider, String model,
            int inputTokens, int outputTokens, long costMicros) {
        jdbc.sql("""
                        INSERT INTO usage_events (id, tenant_id, event_type, source, input_tokens, output_tokens,
                                                  estimated_cost_micros, provider, model, reference_id, occurred_at)
                        VALUES (:id, :tenantId, :type, :source, :input, :output, :cost, :provider, :model,
                                :referenceId, :occurredAt)
                        """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("type", type)
                .param("source", source)
                .param("input", Math.max(inputTokens, 0))
                .param("output", Math.max(outputTokens, 0))
                .param("cost", costMicros)
                .param("provider", provider)
                .param("model", model)
                .param("referenceId", referenceId)
                .param("occurredAt", Timestamp.from(clock.instant()))
                .update();
    }
}
