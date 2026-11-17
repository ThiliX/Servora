package com.servora.privacy;

import com.servora.audit.AuditService;
import com.servora.common.error.ApiException;
import com.servora.common.ratelimit.RateLimiter;
import com.servora.membership.Permission;
import com.servora.membership.WorkspaceAccess;
import com.servora.membership.WorkspaceContext;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workspace data controls for owners (FR-042): the conversation retention period, deleting one
 * conversation, and exporting the workspace's data. Scope and limits are in ADR-020.
 */
@Service
public class DataService {

    public static final int MIN_RETENTION_DAYS = 30;
    public static final int MAX_RETENTION_DAYS = 3650;

    public record Retention(Integer conversationRetentionDays) {}

    private final WorkspaceAccess access;
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final RateLimiter rateLimiter;
    private final DataProperties.Export exportProperties;
    private final Clock clock;

    public DataService(
            WorkspaceAccess access,
            JdbcClient jdbc,
            AuditService audit,
            RateLimiter rateLimiter,
            DataProperties.Export exportProperties,
            Clock clock) {
        this.access = access;
        this.jdbc = jdbc;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.exportProperties = exportProperties;
        this.clock = clock;
    }

    @Transactional
    public Retention setRetention(UUID userId, UUID workspaceId, Integer days) {
        WorkspaceContext ctx = access.authorize(userId, workspaceId, Permission.MANAGE_DATA);
        if (days != null && (days < MIN_RETENTION_DAYS || days > MAX_RETENTION_DAYS)) {
            throw ApiException.badRequest(
                    "INVALID_RETENTION", "The retention period must be between 30 and 3650 days, or empty to keep conversations.");
        }
        jdbc.sql("UPDATE tenants SET conversation_retention_days = :days, updated_at = now() WHERE id = :id")
                .param("days", days)
                .param("id", ctx.tenantId())
                .update();
        audit.record(ctx, "RETENTION_UPDATED", "workspace", ctx.tenantId(),
                Map.of("conversation_retention_days", days == null ? "none" : String.valueOf(days)));
        return new Retention(days);
    }

    /** Deletes a conversation with its messages, ticket, notes, rating and AI records. Cannot be undone. */
    @Transactional
    public void deleteConversation(UUID userId, UUID workspaceId, UUID conversationId) {
        WorkspaceContext ctx = access.authorize(userId, workspaceId, Permission.MANAGE_DATA);
        boolean deleted = Boolean.TRUE.equals(jdbc.sql("SELECT delete_conversation_data(:id)")
                .param("id", conversationId)
                .query(Boolean.class)
                .single());
        if (!deleted) {
            throw ApiException.notFound("CONVERSATION_NOT_FOUND", "The conversation was not found.");
        }
        audit.record(ctx, "CONVERSATION_DELETED", "conversation", conversationId, Map.of("reason", "MANUAL"));
    }

    /**
     * Everything the workspace holds that its owner may take away: settings, team, configuration,
     * document metadata, conversations with their public messages, tickets, notes, ratings, the audit
     * log and monthly usage. Never secrets: no password hashes, token hashes or document contents.
     */
    @Transactional
    public Map<String, Object> export(UUID userId, UUID workspaceId) {
        WorkspaceContext ctx = access.authorize(userId, workspaceId, Permission.MANAGE_DATA);
        if (!rateLimiter.tryAcquire("export:" + ctx.tenantId(), exportProperties.perWorkspacePerHour(), Duration.ofHours(1))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "EXPORT_RATE_LIMITED", "Too many exports this hour. Try again later.");
        }
        UUID tenant = ctx.tenantId();
        int max = exportProperties.maxConversations();

        Map<String, Object> export = new LinkedHashMap<>();
        export.put("format", "servora-workspace-export");
        export.put("format_version", 1);
        export.put("exported_at", clock.instant().toString());
        export.put("workspace", one("""
                SELECT id, name, slug, status, timezone, default_language, industry, logo_url,
                       conversation_retention_days, created_at
                FROM tenants WHERE id = :tenant
                """, tenant));
        export.put("subscription", one("SELECT plan_code, status, started_at FROM subscriptions WHERE tenant_id = :tenant", tenant));
        export.put("members", rows("""
                SELECT m.id, u.display_name, u.email, m.role, m.status, m.joined_at
                FROM memberships m JOIN users u ON u.id = m.user_id
                WHERE m.tenant_id = :tenant ORDER BY m.joined_at, m.id
                """, tenant));
        export.put("invitations", rows("""
                SELECT id, email, role, status, expires_at, accepted_at, created_at
                FROM invitations WHERE tenant_id = :tenant ORDER BY created_at, id
                """, tenant));
        export.put("assistant", one("""
                SELECT name, welcome_message, fallback_message, language, tone, automation_enabled,
                       confidence_policy, current_version, updated_at
                FROM ai_assistants WHERE tenant_id = :tenant
                """, tenant));
        export.put("widgets", rows("""
                SELECT w.id, w.public_key, w.status, w.title, w.theme_color, w.position, w.show_branding,
                       ARRAY(SELECT o.origin FROM widget_allowed_origins o
                             WHERE o.tenant_id = w.tenant_id AND o.widget_id = w.id ORDER BY o.origin) AS allowed_origins
                FROM widgets w WHERE w.tenant_id = :tenant ORDER BY w.created_at, w.id
                """, tenant));
        export.put("documents", rows("""
                SELECT id, lineage_id, version, original_filename, detected_mime, size_bytes, status, created_at
                FROM documents WHERE tenant_id = :tenant ORDER BY created_at, id
                """, tenant));

        List<Map<String, Object>> conversations = rows("""
                SELECT id, customer_id, status, control_mode, origin, created_at, last_message_at
                FROM conversations WHERE tenant_id = :tenant
                ORDER BY created_at DESC, id LIMIT :limit
                """, Map.of("tenant", tenant, "limit", max + 1));
        boolean truncated = conversations.size() > max;
        if (truncated) {
            conversations = new ArrayList<>(conversations.subList(0, max));
        }
        List<UUID> ids = conversations.stream().map(c -> (UUID) c.get("id")).toList();
        if (!ids.isEmpty()) {
            Map<UUID, List<Map<String, Object>>> messages = new LinkedHashMap<>();
            for (Map<String, Object> message : rows("""
                    SELECT conversation_id, id, sender_type, body, created_at
                    FROM messages
                    WHERE tenant_id = :tenant AND conversation_id IN (:ids) AND visibility = 'PUBLIC'
                    ORDER BY created_at, id
                    """, Map.of("tenant", tenant, "ids", ids))) {
                UUID conversationId = (UUID) message.remove("conversation_id");
                messages.computeIfAbsent(conversationId, k -> new ArrayList<>()).add(message);
            }
            for (Map<String, Object> conversation : conversations) {
                conversation.put("messages", messages.getOrDefault((UUID) conversation.get("id"), List.of()));
            }
        }
        export.put("conversations", conversations);
        export.put("conversations_truncated", truncated);
        export.put("tickets", rows("""
                SELECT id, number, conversation_id, status, priority, reason, assignee_membership_id,
                       resolution_outcome, resolution_note, first_response_at, resolved_at, closed_at, created_at
                FROM tickets WHERE tenant_id = :tenant ORDER BY number
                """, tenant));
        export.put("ticket_events", rows("""
                SELECT ticket_id, event_type, actor_membership_id, from_value, to_value, created_at
                FROM ticket_events WHERE tenant_id = :tenant ORDER BY created_at, id
                """, tenant));
        export.put("internal_notes", rows("""
                SELECT id, conversation_id, author_membership_id, body, created_at
                FROM internal_notes WHERE tenant_id = :tenant ORDER BY created_at, id
                """, tenant));
        export.put("feedback", rows("""
                SELECT conversation_id, rating, comment, created_at
                FROM conversation_feedback WHERE tenant_id = :tenant ORDER BY created_at, id
                """, tenant));
        export.put("audit_events", rows("""
                SELECT id, actor_user_id, action, resource_type, resource_id, metadata::text AS metadata, created_at
                FROM audit_events WHERE tenant_id = :tenant ORDER BY created_at, id
                """, tenant));
        export.put("monthly_usage", rows("""
                SELECT to_char(date_trunc('month', occurred_at AT TIME ZONE 'UTC'), 'YYYY-MM') AS month, event_type,
                       count(*) AS events, sum(input_tokens) AS input_tokens, sum(output_tokens) AS output_tokens
                FROM usage_events WHERE tenant_id = :tenant
                GROUP BY 1, 2 ORDER BY 1, 2
                """, tenant));

        audit.record(ctx, "WORKSPACE_EXPORTED", "workspace", tenant,
                Map.of("conversations", String.valueOf(conversations.size()), "truncated", String.valueOf(truncated)));
        return export;
    }

    private Map<String, Object> one(String sql, UUID tenant) {
        List<Map<String, Object>> found = rows(sql, tenant);
        return found.isEmpty() ? null : found.getFirst();
    }

    private List<Map<String, Object>> rows(String sql, UUID tenant) {
        return rows(sql, Map.of("tenant", tenant));
    }

    private List<Map<String, Object>> rows(String sql, Map<String, ?> params) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : jdbc.sql(sql).params(params).query().listOfRows()) {
            Map<String, Object> copy = new LinkedHashMap<>();
            row.forEach((column, value) -> copy.put(column, plain(value)));
            result.add(copy);
        }
        return result;
    }

    /** JDBC types that do not serialise to JSON on their own become plain values. */
    private static Object plain(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof java.time.OffsetDateTime time) {
            return time.toInstant().toString();
        }
        if (value instanceof java.sql.Array array) {
            try {
                return List.of((Object[]) array.getArray());
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        return value;
    }
}
