package com.servora.audit;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.servora.common.web.PageResponse;
import com.servora.membership.Permission;
import com.servora.membership.WorkspaceAccess;
import com.servora.membership.WorkspaceContext;
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
 * Append-only audit log. Plain SQL is used instead of an entity because rows are only ever inserted
 * and listed; the runtime role has no UPDATE or DELETE privilege on the table.
 */
@Service
public class AuditService {

    public record AuditEventView(
            UUID id,
            UUID actorUserId,
            // Null for actions taken by the system, such as the retention job.
            String actorName,
            String action,
            String resourceType,
            UUID resourceId,
            @JsonRawValue String metadata,
            Instant createdAt) {}

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final WorkspaceAccess access;

    public AuditService(JdbcClient jdbc, ObjectMapper json, WorkspaceAccess access) {
        this.jdbc = jdbc;
        this.json = json;
        this.access = access;
    }

    /**
     * Records an event in the caller's transaction, so the event exists only if the change commits.
     * Metadata must not contain secrets, tokens or message content.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(
            WorkspaceContext ctx, String action, String resourceType, UUID resourceId, Map<String, String> metadata) {
        recordFor(ctx.tenantId(), ctx.userId(), action, resourceType, resourceId, metadata);
    }

    /**
     * For actions that are not taken by a member of the workspace: a platform operator, or the system
     * itself ({@code actorUserId} null). The workspace must already be bound.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordFor(
            UUID tenantId, UUID actorUserId, String action, String resourceType, UUID resourceId,
            Map<String, String> metadata) {
        jdbc.sql("""
                        INSERT INTO audit_events (id, tenant_id, actor_user_id, action, resource_type, resource_id, metadata)
                        VALUES (:id, :tenantId, :actorUserId, :action, :resourceType, :resourceId, CAST(:metadata AS jsonb))
                        """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("actorUserId", actorUserId)
                .param("action", action)
                .param("resourceType", resourceType)
                .param("resourceId", resourceId)
                .param("metadata", json.writeValueAsString(metadata))
                .update();
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditEventView> list(UUID userId, UUID workspaceId, int page, int size) {
        WorkspaceContext ctx = access.authorize(userId, workspaceId, Permission.VIEW_AUDIT_LOG);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), PageResponse.MAX_SIZE);

        List<AuditEventView> items = jdbc.sql("""
                        SELECT a.id, a.actor_user_id, u.display_name AS actor_name, a.action, a.resource_type,
                               a.resource_id, a.metadata::text AS metadata, a.created_at
                        FROM audit_events a LEFT JOIN users u ON u.id = a.actor_user_id
                        WHERE a.tenant_id = :tenantId
                        ORDER BY a.created_at DESC, a.id
                        LIMIT :limit OFFSET :offset
                        """)
                .param("tenantId", ctx.tenantId())
                .param("limit", safeSize)
                .param("offset", (long) safePage * safeSize)
                .query((rs, rowNum) -> new AuditEventView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("actor_user_id", UUID.class),
                        rs.getString("actor_name"),
                        rs.getString("action"),
                        rs.getString("resource_type"),
                        rs.getObject("resource_id", UUID.class),
                        rs.getString("metadata"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
        long total = jdbc.sql("SELECT count(*) FROM audit_events WHERE tenant_id = :tenantId")
                .param("tenantId", ctx.tenantId())
                .query(Long.class)
                .single();
        return new PageResponse<>(items, safePage, safeSize, total);
    }
}
