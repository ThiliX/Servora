package com.servora.conversation;

import static com.servora.conversation.ConversationService.instant;

import com.servora.common.error.ApiException;
import com.servora.common.web.PageResponse;
import com.servora.membership.Permission;
import com.servora.membership.WorkspaceAccess;
import com.servora.membership.WorkspaceContext;
import com.servora.ticket.StaffAccess;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only conversation views for the dashboard. Taking part in conversations arrives in M5. */
@Service
public class ConversationAdminService {

    private static final int PREVIEW_CHARS = 120;

    public record ConversationSummary(
            UUID id,
            String status,
            String controlMode,
            String origin,
            Instant createdAt,
            Instant lastMessageAt,
            Instant handoffRequestedAt,
            long messageCount,
            String lastMessagePreview) {}

    public record AdminCitation(
            UUID chunkId, UUID documentId, String filename, Integer pageNumber, String heading, String snippet) {}

    public record AdminMessage(
            UUID id,
            String senderType,
            String visibility,
            String deliveryStatus,
            String body,
            Instant createdAt,
            String outcome,
            List<AdminCitation> citations) {}

    private static final String SUMMARY = """
            SELECT c.id, c.status, c.control_mode, c.origin, c.created_at, c.last_message_at, c.handoff_requested_at,
                   (SELECT count(*) FROM messages m WHERE m.tenant_id = c.tenant_id AND m.conversation_id = c.id) AS message_count,
                   (SELECT m.body FROM messages m
                    WHERE m.tenant_id = c.tenant_id AND m.conversation_id = c.id AND m.visibility = 'PUBLIC'
                    ORDER BY m.created_at DESC, m.id DESC LIMIT 1) AS last_body
            FROM conversations c
            WHERE c.tenant_id = :tenantId
            """;

    private final JdbcClient jdbc;
    private final WorkspaceAccess access;
    private final StaffAccess staff;

    public ConversationAdminService(JdbcClient jdbc, WorkspaceAccess access, StaffAccess staff) {
        this.jdbc = jdbc;
        this.access = access;
        this.staff = staff;
    }

    @Transactional(readOnly = true)
    public PageResponse<ConversationSummary> list(UUID userId, UUID workspaceId, int page, int size) {
        WorkspaceContext ctx = access.authorize(userId, workspaceId, Permission.VIEW_ALL_CONVERSATIONS);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), PageResponse.MAX_SIZE);
        // Conversations with no message yet (an opened but unused chat) are not listed.
        List<ConversationSummary> items = jdbc.sql(SUMMARY + """
                          AND c.last_message_at IS NOT NULL
                        ORDER BY c.last_message_at DESC, c.id
                        LIMIT :limit OFFSET :offset
                        """)
                .param("tenantId", ctx.tenantId())
                .param("limit", safeSize)
                .param("offset", (long) safePage * safeSize)
                .query(ConversationAdminService::summary)
                .list();
        long total = jdbc.sql("SELECT count(*) FROM conversations WHERE tenant_id = :tenantId AND last_message_at IS NOT NULL")
                .param("tenantId", ctx.tenantId())
                .query(Long.class)
                .single();
        return new PageResponse<>(items, safePage, safeSize, total);
    }

    @Transactional(readOnly = true)
    public ConversationSummary get(UUID userId, UUID workspaceId, UUID conversationId) {
        // Agents may open conversations of tickets assigned to them, or unassigned ones.
        WorkspaceContext ctx = staff.authorize(userId, workspaceId);
        staff.requireConversation(ctx, conversationId);
        return require(ctx, conversationId);
    }

    /** Every message, oldest first, including internal, suppressed and suggested ones. */
    @Transactional(readOnly = true)
    public PageResponse<AdminMessage> messages(UUID userId, UUID workspaceId, UUID conversationId, int page, int size) {
        WorkspaceContext ctx = staff.authorize(userId, workspaceId);
        staff.requireConversation(ctx, conversationId);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), PageResponse.MAX_SIZE);
        List<AdminMessage> rows = jdbc.sql("""
                        SELECT m.id, m.sender_type, m.visibility, m.delivery_status, m.body, m.created_at, i.outcome
                        FROM messages m
                        LEFT JOIN ai_interactions i ON i.tenant_id = m.tenant_id AND i.id = m.interaction_id
                        WHERE m.tenant_id = :tenantId AND m.conversation_id = :conversationId
                        ORDER BY m.created_at, m.id
                        LIMIT :limit OFFSET :offset
                        """)
                .param("tenantId", ctx.tenantId())
                .param("conversationId", conversationId)
                .param("limit", safeSize)
                .param("offset", (long) safePage * safeSize)
                .query((rs, n) -> new AdminMessage(
                        rs.getObject("id", UUID.class),
                        rs.getString("sender_type"),
                        rs.getString("visibility"),
                        rs.getString("delivery_status"),
                        rs.getString("body"),
                        instant(rs, "created_at"),
                        rs.getString("outcome"),
                        List.of()))
                .list();
        long total = jdbc.sql("SELECT count(*) FROM messages WHERE tenant_id = :tenantId AND conversation_id = :conversationId")
                .param("tenantId", ctx.tenantId())
                .param("conversationId", conversationId)
                .query(Long.class)
                .single();
        return new PageResponse<>(withCitations(ctx.tenantId(), rows), safePage, safeSize, total);
    }

    private ConversationSummary require(WorkspaceContext ctx, UUID conversationId) {
        return jdbc.sql(SUMMARY + " AND c.id = :conversationId")
                .param("tenantId", ctx.tenantId())
                .param("conversationId", conversationId)
                .query(ConversationAdminService::summary)
                .optional()
                .orElseThrow(() -> ApiException.notFound("CONVERSATION_NOT_FOUND", "The conversation was not found."));
    }

    private List<AdminMessage> withCitations(UUID tenantId, List<AdminMessage> messages) {
        if (messages.isEmpty()) {
            return messages;
        }
        Map<UUID, List<AdminCitation>> byMessage = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT message_id, chunk_id, document_id, filename, page_number, heading, snippet
                        FROM message_citations
                        WHERE tenant_id = :tenantId AND message_id IN (:ids)
                        ORDER BY message_id, position
                        """)
                .param("tenantId", tenantId)
                .param("ids", messages.stream().map(AdminMessage::id).toList())
                .query((rs, n) -> {
                    byMessage.computeIfAbsent(rs.getObject("message_id", UUID.class), id -> new ArrayList<>())
                            .add(new AdminCitation(
                                    rs.getObject("chunk_id", UUID.class),
                                    rs.getObject("document_id", UUID.class),
                                    rs.getString("filename"),
                                    (Integer) rs.getObject("page_number"),
                                    rs.getString("heading"),
                                    rs.getString("snippet")));
                    return null;
                })
                .list();
        return messages.stream()
                .map(m -> new AdminMessage(m.id(), m.senderType(), m.visibility(), m.deliveryStatus(), m.body(),
                        m.createdAt(), m.outcome(), byMessage.getOrDefault(m.id(), List.of())))
                .toList();
    }

    private static ConversationSummary summary(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String last = rs.getString("last_body");
        return new ConversationSummary(
                rs.getObject("id", UUID.class),
                rs.getString("status"),
                rs.getString("control_mode"),
                rs.getString("origin"),
                instant(rs, "created_at"),
                instant(rs, "last_message_at"),
                instant(rs, "handoff_requested_at"),
                rs.getLong("message_count"),
                last == null ? null : last.length() > PREVIEW_CHARS ? last.substring(0, PREVIEW_CHARS) + "…" : last);
    }
}
