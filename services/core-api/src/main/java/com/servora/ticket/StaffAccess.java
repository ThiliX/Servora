package com.servora.ticket;

import com.servora.common.error.ApiException;
import com.servora.membership.Permission;
import com.servora.membership.WorkspaceAccess;
import com.servora.membership.WorkspaceContext;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who on the team may see and act on a conversation.
 *
 * <p>Owners, admins and supervisors see every conversation. Agents see only conversations whose
 * ticket is assigned to them, plus unassigned tickets they may pick up. Anything else is reported
 * as not found, exactly like another workspace's conversation.
 */
@Component
public class StaffAccess {

    /** A conversation, and its ticket and assignee when it has one. */
    public record Target(UUID conversationId, UUID ticketId, UUID assigneeMembershipId) {

        public boolean assignedTo(UUID membershipId) {
            return assigneeMembershipId != null && assigneeMembershipId.equals(membershipId);
        }
    }

    private static final String TARGET = """
            SELECT c.id AS conversation_id, t.id AS ticket_id, t.assignee_membership_id
            FROM conversations c
            LEFT JOIN tickets t ON t.tenant_id = c.tenant_id AND t.conversation_id = c.id
            WHERE c.tenant_id = :tenantId
            """;

    private final WorkspaceAccess access;
    private final JdbcClient jdbc;

    public StaffAccess(WorkspaceAccess access, JdbcClient jdbc) {
        this.access = access;
        this.jdbc = jdbc;
    }

    /** Every role may work on customer conversations; which ones is decided per conversation. */
    @Transactional(propagation = Propagation.MANDATORY)
    public WorkspaceContext authorize(UUID userId, UUID workspaceId) {
        return access.authorize(userId, workspaceId, Permission.REPLY_TO_CUSTOMERS);
    }

    public static boolean seesAll(WorkspaceContext ctx) {
        return Permission.VIEW_ALL_CONVERSATIONS.grantedTo(ctx.role());
    }

    public static boolean manages(WorkspaceContext ctx) {
        return Permission.ASSIGN_TICKETS.grantedTo(ctx.role());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Target requireConversation(WorkspaceContext ctx, UUID conversationId) {
        Target target = jdbc.sql(TARGET + " AND c.id = :id")
                .param("tenantId", ctx.tenantId())
                .param("id", conversationId)
                .query((rs, n) -> new Target(
                        rs.getObject("conversation_id", UUID.class),
                        rs.getObject("ticket_id", UUID.class),
                        rs.getObject("assignee_membership_id", UUID.class)))
                .optional()
                .filter(t -> visible(ctx, t))
                .orElseThrow(() -> ApiException.notFound("CONVERSATION_NOT_FOUND", "The conversation was not found."));
        return target;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Target requireTicket(WorkspaceContext ctx, UUID ticketId) {
        return jdbc.sql(TARGET + " AND t.id = :id")
                .param("tenantId", ctx.tenantId())
                .param("id", ticketId)
                .query((rs, n) -> new Target(
                        rs.getObject("conversation_id", UUID.class),
                        rs.getObject("ticket_id", UUID.class),
                        rs.getObject("assignee_membership_id", UUID.class)))
                .optional()
                .filter(t -> visible(ctx, t))
                .orElseThrow(() -> ApiException.notFound("TICKET_NOT_FOUND", "The ticket was not found."));
    }

    /** May the caller change this ticket: its assignee, or anyone who assigns tickets. */
    public static void requireWorker(WorkspaceContext ctx, Target target) {
        if (!manages(ctx) && !target.assignedTo(ctx.membershipId())) {
            throw ApiException.forbidden("NOT_ASSIGNED", "Only the assigned team member can do this. Take over first.");
        }
    }

    private static boolean visible(WorkspaceContext ctx, Target target) {
        if (seesAll(ctx)) {
            return true;
        }
        return target.ticketId() != null
                && (target.assigneeMembershipId() == null || target.assignedTo(ctx.membershipId()));
    }
}
