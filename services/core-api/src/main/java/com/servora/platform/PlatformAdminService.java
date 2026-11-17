package com.servora.platform;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.servora.audit.AuditService;
import com.servora.common.error.ApiException;
import com.servora.common.tenant.TenantContext;
import com.servora.common.web.PageResponse;
import com.servora.usage.UsageService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Platform administration (FR-043, ADR-019): list workspaces, suspend and reactivate them, and
 * assign plans. A separate permission domain from workspace roles.
 *
 * <p>Access needs a row in {@code platform_admins}, which only an operator with database access can
 * add. Every change is written to the platform audit log and to the workspace's own audit log.
 * Nothing here reads conversations, messages, documents or members: only workspace metadata, the
 * plan and usage counts.
 */
@Service
public class PlatformAdminService {

    public record TenantRow(
            UUID id,
            String name,
            String slug,
            String status,
            String suspendedReason,
            String planCode,
            long aiRequestsThisMonth,
            long conversationsThisMonth,
            Instant createdAt) {}

    public record PlatformAuditEvent(
            UUID id, String actorName, String action, UUID targetTenantId, String targetTenantName,
            @JsonRawValue String metadata,
            Instant createdAt) {}

    private final JdbcClient jdbc;
    private final TenantContext tenantContext;
    private final AuditService audit;
    private final UsageService usage;
    private final ObjectMapper json;

    public PlatformAdminService(
            JdbcClient jdbc, TenantContext tenantContext, AuditService audit, UsageService usage, ObjectMapper json) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.audit = audit;
        this.usage = usage;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public boolean isPlatformAdmin(UUID userId) {
        tenantContext.bindUser(userId);
        return isAdmin(userId);
    }

    @Transactional(readOnly = true)
    public PageResponse<TenantRow> tenants(UUID userId, String query, int page, int size) {
        authorize(userId);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), PageResponse.MAX_SIZE);
        String pattern = query == null || query.isBlank() ? null : "%" + escapeLike(query.strip().toLowerCase()) + "%";
        Timestamp start = Timestamp.from(usage.periodStart());

        List<TenantRow> items = jdbc.sql("""
                        SELECT t.id, t.name, t.slug, t.status, t.suspended_reason, s.plan_code, t.created_at,
                               (SELECT count(*) FROM usage_events u WHERE u.tenant_id = t.id
                                  AND u.event_type = 'AI_REQUEST' AND u.occurred_at >= :start) AS ai_requests,
                               (SELECT count(*) FROM usage_events u WHERE u.tenant_id = t.id
                                  AND u.event_type = 'CONVERSATION_STARTED' AND u.occurred_at >= :start) AS conversations
                        FROM tenants t LEFT JOIN subscriptions s ON s.tenant_id = t.id
                        WHERE CAST(:pattern AS text) IS NULL
                           OR lower(t.name) LIKE :pattern ESCAPE '\\' OR t.slug LIKE :pattern ESCAPE '\\'
                        ORDER BY t.created_at DESC, t.id
                        LIMIT :limit OFFSET :offset
                        """)
                .param("start", start)
                .param("pattern", pattern)
                .param("limit", safeSize)
                .param("offset", (long) safePage * safeSize)
                .query((rs, n) -> new TenantRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("slug"),
                        rs.getString("status"),
                        rs.getString("suspended_reason"),
                        rs.getString("plan_code"),
                        rs.getLong("ai_requests"),
                        rs.getLong("conversations"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
        long total = jdbc.sql("""
                        SELECT count(*) FROM tenants t
                        WHERE CAST(:pattern AS text) IS NULL
                           OR lower(t.name) LIKE :pattern ESCAPE '\\' OR t.slug LIKE :pattern ESCAPE '\\'
                        """)
                .param("pattern", pattern)
                .query(Long.class)
                .single();
        return new PageResponse<>(items, safePage, safeSize, total);
    }

    @Transactional
    public TenantRow suspend(UUID userId, UUID tenantId, String reason) {
        authorize(userId);
        String status = requireTenant(tenantId);
        if ("SUSPENDED".equals(status)) {
            throw ApiException.conflict("ALREADY_SUSPENDED", "This workspace is already suspended.");
        }
        jdbc.sql("""
                        UPDATE tenants SET status = 'SUSPENDED', suspended_reason = :reason, updated_at = now()
                        WHERE id = :id
                        """)
                .param("reason", reason)
                .param("id", tenantId)
                .update();
        recordAction(userId, tenantId, "PLATFORM_WORKSPACE_SUSPENDED", Map.of("reason", reason));
        return row(tenantId);
    }

    @Transactional
    public TenantRow reactivate(UUID userId, UUID tenantId) {
        authorize(userId);
        String status = requireTenant(tenantId);
        if ("ACTIVE".equals(status)) {
            throw ApiException.conflict("ALREADY_ACTIVE", "This workspace is already active.");
        }
        jdbc.sql("UPDATE tenants SET status = 'ACTIVE', suspended_reason = NULL, updated_at = now() WHERE id = :id")
                .param("id", tenantId)
                .update();
        recordAction(userId, tenantId, "PLATFORM_WORKSPACE_REACTIVATED", Map.of());
        return row(tenantId);
    }

    @Transactional
    public TenantRow changePlan(UUID userId, UUID tenantId, String planCode) {
        authorize(userId);
        requireTenant(tenantId);
        boolean known = jdbc.sql("SELECT count(*) FROM plans WHERE code = :code")
                        .param("code", planCode)
                        .query(Long.class)
                        .single()
                == 1;
        if (!known) {
            throw ApiException.badRequest("UNKNOWN_PLAN", "There is no plan with that code.");
        }
        String previous = jdbc.sql("SELECT plan_code FROM subscriptions WHERE tenant_id = :id FOR UPDATE")
                .param("id", tenantId)
                .query(String.class)
                .single();
        if (!previous.equals(planCode)) {
            jdbc.sql("UPDATE subscriptions SET plan_code = :code, updated_at = now() WHERE tenant_id = :id")
                    .param("code", planCode)
                    .param("id", tenantId)
                    .update();
            recordAction(userId, tenantId, "PLATFORM_PLAN_CHANGED", Map.of("from", previous, "to", planCode));
        }
        return row(tenantId);
    }

    @Transactional(readOnly = true)
    public PageResponse<PlatformAuditEvent> auditLog(UUID userId, int page, int size) {
        authorize(userId);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), PageResponse.MAX_SIZE);
        List<PlatformAuditEvent> items = jdbc.sql("""
                        SELECT e.id, u.display_name, e.action, e.target_tenant_id, t.name AS tenant_name,
                               e.metadata::text AS metadata, e.created_at
                        FROM platform_audit_events e
                        JOIN users u ON u.id = e.actor_user_id
                        LEFT JOIN tenants t ON t.id = e.target_tenant_id
                        ORDER BY e.created_at DESC, e.id
                        LIMIT :limit OFFSET :offset
                        """)
                .param("limit", safeSize)
                .param("offset", (long) safePage * safeSize)
                .query((rs, n) -> new PlatformAuditEvent(
                        rs.getObject("id", UUID.class),
                        rs.getString("display_name"),
                        rs.getString("action"),
                        rs.getObject("target_tenant_id", UUID.class),
                        rs.getString("tenant_name"),
                        rs.getString("metadata"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
        long total = jdbc.sql("SELECT count(*) FROM platform_audit_events").query(Long.class).single();
        return new PageResponse<>(items, safePage, safeSize, total);
    }

    /**
     * Proves the caller is a platform admin and only then enables cross-tenant reads. Anyone else
     * gets the same answer as for a path that does not exist.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void authorize(UUID userId) {
        tenantContext.bindUser(userId);
        if (!isAdmin(userId)) {
            throw ApiException.notFound("NOT_FOUND", "The resource was not found.");
        }
        tenantContext.enablePlatformAdmin();
    }

    private boolean isAdmin(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM platform_admins WHERE user_id = :userId")
                        .param("userId", userId)
                        .query(Long.class)
                        .single()
                == 1;
    }

    private String requireTenant(UUID tenantId) {
        return jdbc.sql("SELECT status FROM tenants WHERE id = :id FOR UPDATE")
                .param("id", tenantId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("WORKSPACE_NOT_FOUND", "The workspace was not found."));
    }

    private void recordAction(UUID userId, UUID tenantId, String action, Map<String, String> metadata) {
        jdbc.sql("""
                        INSERT INTO platform_audit_events (id, actor_user_id, action, target_tenant_id, metadata)
                        VALUES (:id, :actor, :action, :tenant, CAST(:metadata AS jsonb))
                        """)
                .param("id", UUID.randomUUID())
                .param("actor", userId)
                .param("action", action)
                .param("tenant", tenantId)
                .param("metadata", json.writeValueAsString(metadata))
                .update();
        // The workspace's own audit log shows the operator's action to its owners and admins. The
        // tenant ID was just read under the platform admin policy, so it is a real workspace.
        tenantContext.bindTenant(tenantId);
        audit.recordFor(tenantId, userId, action, "workspace", tenantId, metadata);
    }

    private TenantRow row(UUID tenantId) {
        Timestamp start = Timestamp.from(usage.periodStart());
        return jdbc.sql("""
                        SELECT t.id, t.name, t.slug, t.status, t.suspended_reason, s.plan_code, t.created_at,
                               (SELECT count(*) FROM usage_events u WHERE u.tenant_id = t.id
                                  AND u.event_type = 'AI_REQUEST' AND u.occurred_at >= :start) AS ai_requests,
                               (SELECT count(*) FROM usage_events u WHERE u.tenant_id = t.id
                                  AND u.event_type = 'CONVERSATION_STARTED' AND u.occurred_at >= :start) AS conversations
                        FROM tenants t LEFT JOIN subscriptions s ON s.tenant_id = t.id
                        WHERE t.id = :id
                        """)
                .param("start", start)
                .param("id", tenantId)
                .query((rs, n) -> new TenantRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("slug"),
                        rs.getString("status"),
                        rs.getString("suspended_reason"),
                        rs.getString("plan_code"),
                        rs.getLong("ai_requests"),
                        rs.getLong("conversations"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .single();
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
