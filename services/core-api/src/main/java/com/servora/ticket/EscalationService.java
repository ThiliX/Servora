package com.servora.ticket;

import com.servora.ticket.EscalationPolicy.Decision;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves a conversation to a person and creates its ticket, in the caller's transaction.
 *
 * <p>The conversation row is locked first, so concurrent escalations of one conversation run one
 * after the other, and the ticket's idempotency key is derived from the conversation, so they all
 * end with exactly one ticket. A conversation that is already escalated, taken over or closed is
 * left as it is.
 */
@Service
public class EscalationService {

    static final String HANDOFF_NOTICE = "Thanks. A member of our team will reply here as soon as possible.";

    public record Escalation(UUID ticketId, boolean created, UUID systemMessageId) {}

    private final JdbcClient jdbc;

    public EscalationService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Must run inside a transaction with the tenant bound. Returns the conversation's ticket. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Escalation> escalate(UUID tenantId, UUID conversationId, Decision decision, UUID actorMembershipId) {
        Map<String, Object> conversation = jdbc.sql("""
                        SELECT status, control_mode FROM conversations
                        WHERE tenant_id = :tenantId AND id = :conversationId FOR UPDATE
                        """)
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .query()
                .singleRow();
        Optional<UUID> existing = ticketFor(tenantId, conversationId);
        if (existing.isPresent()) {
            return Optional.of(new Escalation(existing.get(), false, null));
        }
        if (!"OPEN".equals(conversation.get("status"))) {
            return Optional.empty();
        }

        long number = jdbc.sql("""
                        UPDATE tenants SET next_ticket_number = next_ticket_number + 1
                        WHERE id = :tenantId RETURNING next_ticket_number - 1
                        """)
                .param("tenantId", tenantId)
                .query(Long.class)
                .single();
        UUID ticketId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO tickets (id, tenant_id, number, conversation_id, idempotency_key, priority, reason)
                        VALUES (:id, :tenantId, :number, :conversationId, :key, :priority, :reason)
                        """)
                .param("id", ticketId)
                .param("tenantId", tenantId)
                .param("number", number)
                .param("conversationId", conversationId)
                .param("key", "conversation:" + conversationId)
                .param("priority", decision.priority().name())
                .param("reason", decision.reason().name())
                .update();
        event(tenantId, ticketId, "CREATED", actorMembershipId, null, decision.reason().name());

        UUID systemMessageId = null;
        if ("AI_ACTIVE".equals(conversation.get("control_mode"))) {
            jdbc.sql("""
                            UPDATE conversations
                            SET control_mode = 'ESCALATION_PENDING', version = version + 1, handoff_requested_at = now(),
                                updated_at = now(), last_message_at = now()
                            WHERE tenant_id = :tenantId AND id = :conversationId
                            """)
                    .param("tenantId", tenantId)
                    .param("conversationId", conversationId)
                    .update();
            systemMessageId = systemMessage(tenantId, conversationId, HANDOFF_NOTICE);
        }
        return Optional.of(new Escalation(ticketId, true, systemMessageId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> ticketFor(UUID tenantId, UUID conversationId) {
        return jdbc.sql("SELECT id FROM tickets WHERE tenant_id = :tenantId AND conversation_id = :conversationId")
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .query(UUID.class)
                .optional();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void event(UUID tenantId, UUID ticketId, String type, UUID actorMembershipId, String from, String to) {
        jdbc.sql("""
                        INSERT INTO ticket_events (id, tenant_id, ticket_id, event_type, actor_membership_id, from_value, to_value)
                        VALUES (:id, :tenantId, :ticketId, :type, :actor, :from, :to)
                        """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("ticketId", ticketId)
                .param("type", type)
                .param("actor", actorMembershipId)
                .param("from", from)
                .param("to", to)
                .update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID systemMessage(UUID tenantId, UUID conversationId, String body) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO messages (id, tenant_id, conversation_id, sender_type, body)
                        VALUES (:id, :tenantId, :conversationId, 'SYSTEM', :body)
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .param("body", body)
                .update();
        return id;
    }
}
