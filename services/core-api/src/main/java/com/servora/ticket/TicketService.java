package com.servora.ticket;

import static com.servora.conversation.ConversationService.instant;

import com.servora.common.error.ApiException;
import com.servora.common.web.PageResponse;
import com.servora.membership.Permission;
import com.servora.membership.WorkspaceAccess;
import com.servora.audit.AuditService;
import com.servora.membership.WorkspaceContext;
import com.servora.ticket.NotificationService.Type;
import com.servora.ticket.StaffAccess.Target;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Ticket queue, assignment, workflow, takeover and resolution. */
@Service
public class TicketService {

    static final String JOINED_NOTICE = "A member of our team has joined the conversation.";
    static final String RESOLVED_NOTICE = "This conversation has been marked as resolved. Thank you for contacting us.";
    private static final int PREVIEW_CHARS = 120;

    public enum View {
        MINE,
        UNASSIGNED,
        ALL
    }

    public enum State {
        ACTIVE,
        DONE
    }

    public enum Outcome {
        SOLVED,
        NOT_SOLVED,
        NO_REPLY_FROM_CUSTOMER,
        DUPLICATE
    }

    public record TicketSummary(
            UUID id,
            long number,
            String status,
            String priority,
            String reason,
            UUID assigneeMembershipId,
            String assigneeName,
            UUID conversationId,
            String controlMode,
            String conversationStatus,
            int conversationVersion,
            String origin,
            String resolutionOutcome,
            Instant createdAt,
            Instant updatedAt,
            Instant lastMessageAt,
            String lastMessagePreview) {}

    public record TicketEvent(String eventType, String actorName, String fromValue, String toValue, Instant createdAt) {}

    public record Feedback(int rating, String comment, Instant createdAt) {}

    public record TicketDetail(
            TicketSummary ticket, List<TicketEvent> events, Feedback feedback, boolean canWork, boolean assignedToMe) {}

    public record Assignee(UUID membershipId, String displayName, String role) {}

    private static final String SUMMARY = """
            SELECT t.id, t.number, t.status, t.priority, t.reason, t.assignee_membership_id, u.display_name AS assignee_name,
                   c.id AS conversation_id, c.control_mode, c.status AS conversation_status, c.version, c.origin,
                   t.resolution_outcome, t.created_at, t.updated_at, c.last_message_at,
                   (SELECT m.body FROM messages m
                    WHERE m.tenant_id = c.tenant_id AND m.conversation_id = c.id AND m.visibility = 'PUBLIC'
                    ORDER BY m.created_at DESC, m.id DESC LIMIT 1) AS last_body
            FROM tickets t
            JOIN conversations c ON c.tenant_id = t.tenant_id AND c.id = t.conversation_id
            LEFT JOIN memberships mb ON mb.tenant_id = t.tenant_id AND mb.id = t.assignee_membership_id
            LEFT JOIN users u ON u.id = mb.user_id
            WHERE t.tenant_id = :tenantId
            """;

    private final JdbcClient jdbc;
    private final WorkspaceAccess access;
    private final StaffAccess staff;
    private final EscalationService escalation;
    private final NotificationService notifications;
    private final AuditService audit;

    public TicketService(
            JdbcClient jdbc,
            WorkspaceAccess access,
            StaffAccess staff,
            EscalationService escalation,
            NotificationService notifications,
            AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.staff = staff;
        this.escalation = escalation;
        this.notifications = notifications;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<TicketSummary> list(UUID userId, UUID workspaceId, View view, State state, int page, int size) {
        WorkspaceContext ctx = staff.authorize(userId, workspaceId);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), PageResponse.MAX_SIZE);
        String scope = switch (view) {
            case MINE -> " AND t.assignee_membership_id = :me";
            case UNASSIGNED -> " AND t.assignee_membership_id IS NULL";
            // Agents see their own and the unassigned ones; nobody else's.
            case ALL -> StaffAccess.seesAll(ctx) ? "" : " AND (t.assignee_membership_id = :me OR t.assignee_membership_id IS NULL)";
        };
        String status = state == State.ACTIVE
                ? " AND t.status IN ('OPEN', 'IN_PROGRESS', 'WAITING_FOR_CUSTOMER')"
                : " AND t.status IN ('RESOLVED', 'CLOSED')";
        String order = state == State.ACTIVE
                ? """
                   ORDER BY CASE t.priority WHEN 'URGENT' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'NORMAL' THEN 2 ELSE 3 END,
                            t.created_at, t.id"""
                : " ORDER BY t.resolved_at DESC NULLS LAST, t.id";
        List<TicketSummary> items = jdbc.sql(SUMMARY + scope + status + order + " LIMIT :limit OFFSET :offset")
                .param("tenantId", ctx.tenantId())
                .param("me", ctx.membershipId())
                .param("limit", safeSize)
                .param("offset", (long) safePage * safeSize)
                .query(TicketService::summary)
                .list();
        long total = jdbc.sql("SELECT count(*) FROM tickets t WHERE t.tenant_id = :tenantId" + scope + status)
                .param("tenantId", ctx.tenantId())
                .param("me", ctx.membershipId())
                .query(Long.class)
                .single();
        return new PageResponse<>(items, safePage, safeSize, total);
    }

    @Transactional(readOnly = true)
    public TicketDetail detail(UUID userId, UUID workspaceId, UUID ticketId) {
        WorkspaceContext ctx = staff.authorize(userId, workspaceId);
        staff.requireTicket(ctx, ticketId);
        return detail(ctx, ticketId);
    }

    @Transactional(readOnly = true)
    public List<Assignee> assignees(UUID userId, UUID workspaceId) {
        WorkspaceContext ctx = access.authorize(userId, workspaceId, Permission.ASSIGN_TICKETS);
        return jdbc.sql("""
                        SELECT m.id, u.display_name, m.role FROM memberships m JOIN users u ON u.id = m.user_id
                        WHERE m.tenant_id = :tenantId AND m.status = 'ACTIVE'
                        ORDER BY u.display_name, m.id
                        """)
                .param("tenantId", ctx.tenantId())
                .query((rs, n) -> new Assignee(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
                .list();
    }

    @Transactional
    public TicketDetail assign(UUID userId, UUID workspaceId, UUID ticketId, UUID membershipId) {
        WorkspaceContext ctx = access.authorize(userId, workspaceId, Permission.ASSIGN_TICKETS);
        Target target = staff.requireTicket(ctx, ticketId);
        Map<String, Object> ticket = lockTicket(ctx, ticketId);
        if (!TicketStatus.valueOf((String) ticket.get("status")).isActive()) {
            throw ApiException.conflict("TICKET_NOT_ACTIVE", "This ticket is already resolved.");
        }
        if (membershipId != null) {
            boolean activeMember = jdbc.sql("""
                            SELECT count(*) FROM memberships WHERE tenant_id = :tenantId AND id = :id AND status = 'ACTIVE'
                            """)
                            .param("tenantId", ctx.tenantId())
                            .param("id", membershipId)
                            .query(Long.class)
                            .single()
                    == 1;
            if (!activeMember) {
                throw ApiException.badRequest("INVALID_ASSIGNEE", "Choose an active member of this workspace.");
            }
        }
        setAssignee(ctx, target, membershipId);
        audit.record(ctx, "TICKET_ASSIGNED", "ticket", ticketId,
                Map.of("assignee_membership_id", membershipId == null ? "none" : membershipId.toString()));
        return detail(ctx, ticketId);
    }

    /** Changes status (in progress or waiting for the customer) and priority. */
    @Transactional
    public TicketDetail update(UUID userId, UUID workspaceId, UUID ticketId, TicketStatus status, EscalationPolicy.Priority priority) {
        WorkspaceContext ctx = staff.authorize(userId, workspaceId);
        Target target = staff.requireTicket(ctx, ticketId);
        StaffAccess.requireWorker(ctx, target);
        Map<String, Object> ticket = lockTicket(ctx, ticketId);
        if (status != null) {
            if (status != TicketStatus.IN_PROGRESS && status != TicketStatus.WAITING_FOR_CUSTOMER) {
                throw ApiException.badRequest("INVALID_STATUS", "Use resolve or close to finish a ticket.");
            }
            moveTo(ctx, ticketId, TicketStatus.valueOf((String) ticket.get("status")), status, ctx.membershipId(), true);
        }
        if (priority != null && !priority.name().equals(ticket.get("priority"))) {
            jdbc.sql("UPDATE tickets SET priority = :priority, updated_at = now() WHERE tenant_id = :tenantId AND id = :id")
                    .param("priority", priority.name())
                    .param("tenantId", ctx.tenantId())
                    .param("id", ticketId)
                    .update();
            escalation.event(ctx.tenantId(), ticketId, "PRIORITY_CHANGED", ctx.membershipId(),
                    (String) ticket.get("priority"), priority.name());
        }
        return detail(ctx, ticketId);
    }

    /**
     * Takes a conversation over from the assistant. The caller sends the conversation version it
     * last saw; if anything changed since, nothing happens and the caller must look again.
     */
    @Transactional
    public TicketDetail takeover(UUID userId, UUID workspaceId, UUID conversationId, int expectedVersion) {
        WorkspaceContext ctx = staff.authorize(userId, workspaceId);
        Target target = staff.requireConversation(ctx, conversationId);
        UUID me = ctx.membershipId();
        if (target.ticketId() == null && !StaffAccess.manages(ctx)) {
            throw ApiException.forbidden("TAKEOVER_NOT_ALLOWED", "This conversation has not been escalated.");
        }
        if (target.assigneeMembershipId() != null && !target.assignedTo(me) && !StaffAccess.manages(ctx)) {
            throw ApiException.forbidden("NOT_ASSIGNED", "This ticket is assigned to someone else.");
        }
        Map<String, Object> conversation = jdbc.sql("""
                        SELECT status, control_mode, version FROM conversations
                        WHERE tenant_id = :tenantId AND id = :conversationId FOR UPDATE
                        """)
                .param("tenantId", ctx.tenantId())
                .param("conversationId", conversationId)
                .query()
                .singleRow();
        if (!"OPEN".equals(conversation.get("status"))) {
            throw ApiException.conflict("CONVERSATION_CLOSED", "This conversation has ended.");
        }
        if ("HUMAN_ACTIVE".equals(conversation.get("control_mode")) && target.assignedTo(me)) {
            return detail(ctx, target.ticketId());
        }
        if ((Integer) conversation.get("version") != expectedVersion) {
            throw ApiException.conflict(
                    "CONVERSATION_CHANGED", "The conversation changed while you were looking at it. Check it and try again.");
        }

        UUID ticketId = escalation.escalate(ctx.tenantId(), conversationId, EscalationPolicy.agentInitiated(), me)
                .orElseThrow(() -> ApiException.conflict("CONVERSATION_CLOSED", "This conversation has ended."))
                .ticketId();
        jdbc.sql("""
                        UPDATE conversations SET control_mode = 'HUMAN_ACTIVE', version = version + 1, updated_at = now(),
                                                 last_message_at = now()
                        WHERE tenant_id = :tenantId AND id = :conversationId
                        """)
                .param("tenantId", ctx.tenantId())
                .param("conversationId", conversationId)
                .update();
        Map<String, Object> ticket = lockTicket(ctx, ticketId);
        UUID assignee = (UUID) ticket.get("assignee_membership_id");
        if (!me.equals(assignee)) {
            setAssignee(ctx, new Target(conversationId, ticketId, assignee), me);
        }
        TicketStatus status = TicketStatus.valueOf((String) ticket.get("status"));
        if (status != TicketStatus.IN_PROGRESS && status.canMoveTo(TicketStatus.IN_PROGRESS)) {
            moveTo(ctx, ticketId, status, TicketStatus.IN_PROGRESS, me, false);
        }
        escalation.event(ctx.tenantId(), ticketId, "TAKEN_OVER", me, (String) conversation.get("control_mode"), "HUMAN_ACTIVE");
        escalation.systemMessage(ctx.tenantId(), conversationId, JOINED_NOTICE);
        audit.record(ctx, "CONVERSATION_TAKEN_OVER", "conversation", conversationId, Map.of("ticket_id", ticketId.toString()));
        return detail(ctx, ticketId);
    }

    /** Finishes the work: records the outcome and ends the customer's conversation. */
    @Transactional
    public TicketDetail resolve(UUID userId, UUID workspaceId, UUID ticketId, Outcome outcome, String note) {
        WorkspaceContext ctx = staff.authorize(userId, workspaceId);
        Target target = staff.requireTicket(ctx, ticketId);
        StaffAccess.requireWorker(ctx, target);
        jdbc.sql("SELECT id FROM conversations WHERE tenant_id = :tenantId AND id = :id FOR UPDATE")
                .param("tenantId", ctx.tenantId())
                .param("id", target.conversationId())
                .query(UUID.class)
                .single();
        Map<String, Object> ticket = lockTicket(ctx, ticketId);
        TicketStatus status = TicketStatus.valueOf((String) ticket.get("status"));
        if (!status.canMoveTo(TicketStatus.RESOLVED)) {
            throw ApiException.conflict("TICKET_NOT_ACTIVE", "This ticket is already resolved.");
        }
        jdbc.sql("""
                        UPDATE tickets SET status = 'RESOLVED', resolution_outcome = :outcome, resolution_note = :note,
                                           resolved_at = now(), updated_at = now()
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("outcome", outcome.name())
                .param("note", note == null || note.isBlank() ? null : note.strip())
                .param("tenantId", ctx.tenantId())
                .param("id", ticketId)
                .update();
        jdbc.sql("""
                        UPDATE conversations SET status = 'CLOSED', version = version + 1, updated_at = now(),
                                                 last_message_at = now()
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", ctx.tenantId())
                .param("id", target.conversationId())
                .update();
        escalation.event(ctx.tenantId(), ticketId, "RESOLVED", ctx.membershipId(), status.name(), outcome.name());
        escalation.systemMessage(ctx.tenantId(), target.conversationId(), RESOLVED_NOTICE);
        audit.record(ctx, "TICKET_RESOLVED", "ticket", ticketId, Map.of("outcome", outcome.name()));
        return detail(ctx, ticketId);
    }

    @Transactional
    public TicketDetail close(UUID userId, UUID workspaceId, UUID ticketId) {
        WorkspaceContext ctx = staff.authorize(userId, workspaceId);
        Target target = staff.requireTicket(ctx, ticketId);
        StaffAccess.requireWorker(ctx, target);
        Map<String, Object> ticket = lockTicket(ctx, ticketId);
        if (!TicketStatus.valueOf((String) ticket.get("status")).canMoveTo(TicketStatus.CLOSED)) {
            throw ApiException.conflict("TICKET_NOT_RESOLVED", "Resolve the ticket before closing it.");
        }
        jdbc.sql("UPDATE tickets SET status = 'CLOSED', closed_at = now(), updated_at = now() WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", ctx.tenantId())
                .param("id", ticketId)
                .update();
        escalation.event(ctx.tenantId(), ticketId, "CLOSED", ctx.membershipId(), "RESOLVED", "CLOSED");
        audit.record(ctx, "TICKET_CLOSED", "ticket", ticketId, Map.of());
        return detail(ctx, ticketId);
    }

    /** Called when the customer writes in an escalated conversation. Runs in the caller's transaction. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void onCustomerMessage(UUID tenantId, UUID conversationId) {
        Optional<Map<String, Object>> ticket = jdbc.sql("""
                        SELECT id, status, assignee_membership_id FROM tickets
                        WHERE tenant_id = :tenantId AND conversation_id = :conversationId FOR UPDATE
                        """)
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .query()
                .listOfRows()
                .stream()
                .findFirst();
        if (ticket.isEmpty()) {
            return;
        }
        UUID ticketId = (UUID) ticket.get().get("id");
        if (TicketStatus.WAITING_FOR_CUSTOMER.name().equals(ticket.get().get("status"))) {
            jdbc.sql("UPDATE tickets SET status = 'IN_PROGRESS', updated_at = now() WHERE tenant_id = :tenantId AND id = :id")
                    .param("tenantId", tenantId)
                    .param("id", ticketId)
                    .update();
            escalation.event(tenantId, ticketId, "STATUS_CHANGED", null, "WAITING_FOR_CUSTOMER", "IN_PROGRESS");
        }
        UUID assignee = (UUID) ticket.get().get("assignee_membership_id");
        if (assignee != null) {
            notifications.notify(tenantId, assignee, Type.CUSTOMER_MESSAGE, ticketId, conversationId);
        }
    }

    /** Called after an agent's public reply. Runs in the caller's transaction. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void onAgentReply(WorkspaceContext ctx, UUID ticketId) {
        Map<String, Object> ticket = lockTicket(ctx, ticketId);
        jdbc.sql("""
                        UPDATE tickets SET first_response_at = COALESCE(first_response_at, now()), updated_at = now()
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", ctx.tenantId())
                .param("id", ticketId)
                .update();
        TicketStatus status = TicketStatus.valueOf((String) ticket.get("status"));
        if (status.canMoveTo(TicketStatus.WAITING_FOR_CUSTOMER)) {
            moveTo(ctx, ticketId, status, TicketStatus.WAITING_FOR_CUSTOMER, ctx.membershipId(), false);
        }
    }

    private void setAssignee(WorkspaceContext ctx, Target target, UUID membershipId) {
        if (membershipId == null ? target.assigneeMembershipId() == null : membershipId.equals(target.assigneeMembershipId())) {
            return;
        }
        jdbc.sql("UPDATE tickets SET assignee_membership_id = :assignee, updated_at = now() WHERE tenant_id = :tenantId AND id = :id")
                .param("assignee", membershipId)
                .param("tenantId", ctx.tenantId())
                .param("id", target.ticketId())
                .update();
        escalation.event(ctx.tenantId(), target.ticketId(), "ASSIGNED", ctx.membershipId(),
                target.assigneeMembershipId() == null ? null : target.assigneeMembershipId().toString(),
                membershipId == null ? null : membershipId.toString());
        if (membershipId != null && !membershipId.equals(ctx.membershipId())) {
            notifications.notify(ctx.tenantId(), membershipId, Type.TICKET_ASSIGNED, target.ticketId(), target.conversationId());
        }
    }

    private void moveTo(WorkspaceContext ctx, UUID ticketId, TicketStatus from, TicketStatus to, UUID actor, boolean strict) {
        if (from == to) {
            return;
        }
        if (!from.canMoveTo(to)) {
            if (strict) {
                throw ApiException.conflict("INVALID_TRANSITION", "A ticket cannot move from " + from + " to " + to + ".");
            }
            return;
        }
        jdbc.sql("UPDATE tickets SET status = :status, updated_at = now() WHERE tenant_id = :tenantId AND id = :id")
                .param("status", to.name())
                .param("tenantId", ctx.tenantId())
                .param("id", ticketId)
                .update();
        escalation.event(ctx.tenantId(), ticketId, "STATUS_CHANGED", actor, from.name(), to.name());
    }

    private Map<String, Object> lockTicket(WorkspaceContext ctx, UUID ticketId) {
        return jdbc.sql("""
                        SELECT status, priority, assignee_membership_id FROM tickets
                        WHERE tenant_id = :tenantId AND id = :id FOR UPDATE
                        """)
                .param("tenantId", ctx.tenantId())
                .param("id", ticketId)
                .query()
                .singleRow();
    }

    private TicketDetail detail(WorkspaceContext ctx, UUID ticketId) {
        TicketSummary summary = jdbc.sql(SUMMARY + " AND t.id = :id")
                .param("tenantId", ctx.tenantId())
                .param("id", ticketId)
                .query(TicketService::summary)
                .single();
        List<TicketEvent> events = jdbc.sql("""
                        SELECT e.event_type, u.display_name, e.from_value, e.to_value, e.created_at
                        FROM ticket_events e
                        LEFT JOIN memberships m ON m.tenant_id = e.tenant_id AND m.id = e.actor_membership_id
                        LEFT JOIN users u ON u.id = m.user_id
                        WHERE e.tenant_id = :tenantId AND e.ticket_id = :id
                        ORDER BY e.created_at, e.id
                        """)
                .param("tenantId", ctx.tenantId())
                .param("id", ticketId)
                .query((rs, n) -> new TicketEvent(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), instant(rs, "created_at")))
                .list();
        Feedback feedback = jdbc.sql("""
                        SELECT rating, comment, created_at FROM conversation_feedback
                        WHERE tenant_id = :tenantId AND conversation_id = :conversationId
                        """)
                .param("tenantId", ctx.tenantId())
                .param("conversationId", summary.conversationId())
                .query((rs, n) -> new Feedback(rs.getInt(1), rs.getString(2), instant(rs, "created_at")))
                .optional()
                .orElse(null);
        boolean canWork = StaffAccess.manages(ctx) || ctx.membershipId().equals(summary.assigneeMembershipId());
        return new TicketDetail(summary, events, feedback, canWork, ctx.membershipId().equals(summary.assigneeMembershipId()));
    }

    private static TicketSummary summary(ResultSet rs, int rowNum) throws SQLException {
        String last = rs.getString("last_body");
        return new TicketSummary(
                rs.getObject("id", UUID.class),
                rs.getLong("number"),
                rs.getString("status"),
                rs.getString("priority"),
                rs.getString("reason"),
                rs.getObject("assignee_membership_id", UUID.class),
                rs.getString("assignee_name"),
                rs.getObject("conversation_id", UUID.class),
                rs.getString("control_mode"),
                rs.getString("conversation_status"),
                rs.getInt("version"),
                rs.getString("origin"),
                rs.getString("resolution_outcome"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "last_message_at"),
                last == null ? null : last.length() > PREVIEW_CHARS ? last.substring(0, PREVIEW_CHARS) + "…" : last);
    }
}
