package com.servora.ticket;

import static com.servora.conversation.ConversationService.instant;

import com.servora.membership.Permission;
import com.servora.membership.WorkspaceAccess;
import com.servora.membership.WorkspaceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** In-app notifications: a ticket was assigned to you, or a customer wrote in your ticket. */
@Service
public class NotificationService {

    private static final int LIMIT = 50;

    public enum Type {
        TICKET_ASSIGNED,
        CUSTOMER_MESSAGE
    }

    public record NotificationView(
            UUID id, String type, UUID ticketId, Long ticketNumber, UUID conversationId, Instant createdAt, boolean read) {}

    public record NotificationList(long unreadCount, List<NotificationView> items) {}

    private final JdbcClient jdbc;
    private final WorkspaceAccess access;

    public NotificationService(JdbcClient jdbc, WorkspaceAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    /** Must run inside a transaction with the tenant bound. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notify(UUID tenantId, UUID recipientMembershipId, Type type, UUID ticketId, UUID conversationId) {
        jdbc.sql("""
                        INSERT INTO notification_events (id, tenant_id, recipient_membership_id, event_type, ticket_id,
                                                         conversation_id)
                        VALUES (:id, :tenantId, :recipient, :type, :ticketId, :conversationId)
                        """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("recipient", recipientMembershipId)
                .param("type", type.name())
                .param("ticketId", ticketId)
                .param("conversationId", conversationId)
                .update();
    }

    @Transactional(readOnly = true)
    public NotificationList mine(UUID userId, UUID workspaceId) {
        WorkspaceContext ctx = access.authorize(userId, workspaceId, Permission.VIEW_WORKSPACE);
        List<NotificationView> items = jdbc.sql("""
                        SELECT n.id, n.event_type, n.ticket_id, t.number, n.conversation_id, n.created_at, n.read_at
                        FROM notification_events n
                        LEFT JOIN tickets t ON t.tenant_id = n.tenant_id AND t.id = n.ticket_id
                        WHERE n.tenant_id = :tenantId AND n.recipient_membership_id = :me
                        ORDER BY n.created_at DESC, n.id
                        LIMIT :limit
                        """)
                .param("tenantId", ctx.tenantId())
                .param("me", ctx.membershipId())
                .param("limit", LIMIT)
                .query((rs, n) -> new NotificationView(
                        rs.getObject("id", UUID.class),
                        rs.getString("event_type"),
                        rs.getObject("ticket_id", UUID.class),
                        (Long) rs.getObject("number"),
                        rs.getObject("conversation_id", UUID.class),
                        instant(rs, "created_at"),
                        rs.getObject("read_at") != null))
                .list();
        long unread = jdbc.sql("""
                        SELECT count(*) FROM notification_events
                        WHERE tenant_id = :tenantId AND recipient_membership_id = :me AND read_at IS NULL
                        """)
                .param("tenantId", ctx.tenantId())
                .param("me", ctx.membershipId())
                .query(Long.class)
                .single();
        return new NotificationList(unread, items);
    }

    /** Marks the caller's own notifications read: the given ones, or all when {@code ids} is empty. */
    @Transactional
    public NotificationList markRead(UUID userId, UUID workspaceId, List<UUID> ids) {
        WorkspaceContext ctx = access.authorize(userId, workspaceId, Permission.VIEW_WORKSPACE);
        String filter = ids == null || ids.isEmpty() ? "" : " AND id IN (:ids)";
        var statement = jdbc.sql("""
                        UPDATE notification_events SET read_at = now()
                        WHERE tenant_id = :tenantId AND recipient_membership_id = :me AND read_at IS NULL
                        """ + filter)
                .param("tenantId", ctx.tenantId())
                .param("me", ctx.membershipId());
        if (!filter.isEmpty()) {
            statement = statement.param("ids", ids);
        }
        statement.update();
        return mine(userId, workspaceId);
    }
}
