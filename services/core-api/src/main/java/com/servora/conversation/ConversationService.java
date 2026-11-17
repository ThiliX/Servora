package com.servora.conversation;

import com.servora.ai.AiAnswer;
import com.servora.ai.AiAnswer.Citation;
import com.servora.ai.AiClient;
import com.servora.ai.AiClient.AiUnavailableException;
import com.servora.ai.AnswerVerifier;
import com.servora.ai.InteractionRecorder;
import com.servora.ai.InteractionRecorder.Source;
import com.servora.assistant.AssistantService.AssistantView;
import com.servora.assistant.AssistantService.ConfidencePolicy;
import com.servora.assistant.AssistantService.Tone;
import com.servora.common.error.ApiException;
import com.servora.common.ratelimit.RateLimiter;
import com.servora.common.tenant.TenantContext;
import com.servora.conversation.WidgetTokens.WidgetSession;
import com.servora.ticket.EscalationPolicy;
import com.servora.ticket.EscalationPolicy.Decision;
import com.servora.ticket.EscalationService;
import com.servora.ticket.EscalationService.Escalation;
import com.servora.ticket.TicketService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The customer side of a conversation: history, sending a message, the AI reply, and asking for a
 * person. Every method works only on the conversation named in the caller's session token.
 *
 * <p>An AI reply is generated without holding a database connection. Before it is stored as
 * delivered, the conversation row is locked and re-read: if a person has taken over, or anything
 * else changed its version, the reply is kept as an internal, suppressed record and never shown
 * to the customer (ADR-014).
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private static final int HISTORY_LIMIT = 200;

    public record CitationView(String filename, Integer pageNumber, String heading, String snippet) {}

    public record MessageView(UUID id, String senderType, String body, Instant createdAt, List<CitationView> citations) {}

    public record ConversationView(
            UUID conversationId, String status, String controlMode, boolean feedbackSubmitted, List<MessageView> messages) {}

    /** What happened to a submitted message. */
    public record Submission(
            MessageView customerMessage, boolean duplicate, MessageView existingReply, boolean replyExpected,
            String notice, int conversationVersion, MessageView systemMessage) {}

    public record Reply(MessageView message, boolean delivered, String outcome, MessageView systemMessage) {}

    private record ConversationState(String status, String controlMode, int version) {}

    private final JdbcClient jdbc;
    private final TenantContext tenantContext;
    private final AiClient ai;
    private final AnswerVerifier verifier;
    private final InteractionRecorder recorder;
    private final RateLimiter rateLimiter;
    private final WidgetProperties properties;
    private final EscalationService escalation;
    private final TicketService tickets;
    private final TransactionTemplate transactions;

    public ConversationService(
            JdbcClient jdbc,
            TenantContext tenantContext,
            AiClient ai,
            AnswerVerifier verifier,
            InteractionRecorder recorder,
            RateLimiter rateLimiter,
            WidgetProperties properties,
            EscalationService escalation,
            TicketService tickets,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.ai = ai;
        this.verifier = verifier;
        this.recorder = recorder;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.escalation = escalation;
        this.tickets = tickets;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public ConversationView history(WidgetSession session) {
        limit("history-conversation:" + session.conversationId(),
                properties.historyPerConversationPerMinute(), Duration.ofMinutes(1));
        return transactions.execute(status -> {
            tenantContext.bindTenant(session.tenantId());
            return view(session);
        });
    }

    public Submission submit(WidgetSession session, String idempotencyKey, String text, String clientAddress) {
        limit("messages-conversation:" + session.conversationId(),
                properties.messagesPerConversationPerMinute(), Duration.ofMinutes(1));
        limit("messages-ip:" + RateLimiter.hashed(clientAddress), properties.messagesPerIpPerMinute(), Duration.ofMinutes(1));
        return transactions.execute(status -> {
            tenantContext.bindTenant(session.tenantId());
            ConversationState state = state(session, false);
            if ("CLOSED".equals(state.status())) {
                throw ApiException.conflict("CONVERSATION_CLOSED", "This conversation has ended. Start a new one.");
            }
            Optional<UUID> inserted = jdbc.sql("""
                            INSERT INTO messages (id, tenant_id, conversation_id, sender_type, body, idempotency_key)
                            VALUES (:id, :tenantId, :conversationId, 'CUSTOMER', :body, :key)
                            ON CONFLICT ON CONSTRAINT uq_messages_idempotency DO NOTHING
                            RETURNING id
                            """)
                    .param("id", UUID.randomUUID())
                    .param("tenantId", session.tenantId())
                    .param("conversationId", session.conversationId())
                    .param("body", text)
                    .param("key", idempotencyKey)
                    .query(UUID.class)
                    .optional();
            if (inserted.isEmpty()) {
                return duplicate(session, idempotencyKey, text, state);
            }
            jdbc.sql("""
                            UPDATE conversations SET last_message_at = now(), updated_at = now()
                            WHERE tenant_id = :tenantId AND id = :conversationId
                            """)
                    .param("tenantId", session.tenantId())
                    .param("conversationId", session.conversationId())
                    .update();
            jdbc.sql("UPDATE customers SET last_seen_at = now() WHERE tenant_id = :tenantId AND id = :customerId")
                    .param("tenantId", session.tenantId())
                    .param("customerId", session.customerId())
                    .update();
            MessageView message = message(session.tenantId(), inserted.get());
            boolean automation = jdbc.sql("SELECT automation_enabled FROM ai_assistants WHERE tenant_id = :tenantId")
                    .param("tenantId", session.tenantId())
                    .query(Boolean.class)
                    .optional()
                    .orElse(false);
            if (!"AI_ACTIVE".equals(state.controlMode())) {
                tickets.onCustomerMessage(session.tenantId(), session.conversationId());
                return new Submission(message, false, null, false, "HUMAN_PENDING", state.version(), null);
            }
            Optional<Decision> risk = EscalationPolicy.forCustomerMessage(text);
            if (risk.isPresent()) {
                MessageView notice = escalation.escalate(session.tenantId(), session.conversationId(), risk.get(), null)
                        .map(Escalation::systemMessageId)
                        .map(id -> message(session.tenantId(), id))
                        .orElse(null);
                return new Submission(message, false, null, false, "HUMAN_PENDING", state.version(), notice);
            }
            if (!automation) {
                return new Submission(message, false, null, false, "AUTOMATION_OFF", state.version(), null);
            }
            return new Submission(message, false, null, true, null, state.version(), null);
        });
    }

    /** Asks the AI service, verifies the reply, and stores it as delivered only if the conversation is unchanged. */
    public Reply generateReply(WidgetSession session, Submission submission) {
        AssistantView assistant = transactions.execute(status -> {
            tenantContext.bindTenant(session.tenantId());
            return assistant(session.tenantId());
        });
        String question = submission.customerMessage().body();

        AiAnswer answer = null;
        if (withinTenantBudget(session.tenantId())) {
            try {
                answer = ai.answer(session.tenantId(), assistant, question);
            } catch (AiUnavailableException e) {
                log.warn("AI service unavailable: {}", e.getMessage());
            }
        }
        AiAnswer received = AnswerVerifier.normalise(answer, assistant.fallbackMessage());

        Reply reply = transactions.execute(status -> {
            tenantContext.bindTenant(session.tenantId());
            AiAnswer verified = verifier.verify(session.tenantId(), assistant.fallbackMessage(), received);
            UUID interactionId = recorder.record(
                    session.tenantId(), Source.WIDGET, assistant.currentVersion(), null, question, verified);
            ConversationState now = state(session, true);
            boolean deliverable = "OPEN".equals(now.status())
                    && "AI_ACTIVE".equals(now.controlMode())
                    && now.version() == submission.conversationVersion();
            boolean previousUnusable = EscalationPolicy.isUnusable(previousAssistantOutcome(session));
            UUID messageId = UUID.randomUUID();
            jdbc.sql("""
                            INSERT INTO messages (id, tenant_id, conversation_id, sender_type, visibility, delivery_status,
                                                  body, reply_to_id, interaction_id)
                            VALUES (:id, :tenantId, :conversationId, 'ASSISTANT', :visibility, :delivery, :body,
                                    :replyTo, :interactionId)
                            """)
                    .param("id", messageId)
                    .param("tenantId", session.tenantId())
                    .param("conversationId", session.conversationId())
                    .param("visibility", deliverable ? "PUBLIC" : "INTERNAL")
                    .param("delivery", deliverable ? "DELIVERED" : "SUPPRESSED")
                    .param("body", verified.answer())
                    .param("replyTo", submission.customerMessage().id())
                    .param("interactionId", interactionId)
                    .update();
            int position = 0;
            for (Citation citation : verified.citations()) {
                jdbc.sql("""
                                INSERT INTO message_citations (id, tenant_id, message_id, position, chunk_id, document_id,
                                                               filename, page_number, heading, snippet)
                                VALUES (:id, :tenantId, :messageId, :position, :chunkId, :documentId, :filename,
                                        :pageNumber, :heading, :snippet)
                                """)
                        .param("id", UUID.randomUUID())
                        .param("tenantId", session.tenantId())
                        .param("messageId", messageId)
                        .param("position", position++)
                        .param("chunkId", citation.chunkId())
                        .param("documentId", citation.documentId())
                        .param("filename", citation.filename())
                        .param("pageNumber", citation.pageNumber())
                        .param("heading", citation.heading())
                        .param("snippet", citation.snippet() == null ? "" : citation.snippet())
                        .update();
            }
            MessageView notice = null;
            if (deliverable) {
                jdbc.sql("""
                                UPDATE conversations SET last_message_at = now(), updated_at = now()
                                WHERE tenant_id = :tenantId AND id = :conversationId
                                """)
                        .param("tenantId", session.tenantId())
                        .param("conversationId", session.conversationId())
                        .update();
                Optional<Decision> decision = EscalationPolicy.afterAiReply(
                        verified.outcome(), verified.intent(), verified.escalationReason(), previousUnusable);
                if (decision.isPresent()) {
                    notice = escalation.escalate(session.tenantId(), session.conversationId(), decision.get(), null)
                            .map(Escalation::systemMessageId)
                            .map(id -> message(session.tenantId(), id))
                            .orElse(null);
                }
            }
            return new Reply(message(session.tenantId(), messageId), deliverable, verified.outcome(), notice);
        });
        // Identifiers and outcome only: never the question or the answer text.
        log.info("widget reply tenant={} conversation={} outcome={} delivered={}",
                session.tenantId(), session.conversationId(), reply.outcome(), reply.delivered());
        return reply;
    }

    /** Stops AI replies and asks for a person. Repeating it changes nothing. */
    public ConversationView requestHuman(WidgetSession session) {
        return transactions.execute(status -> {
            tenantContext.bindTenant(session.tenantId());
            ConversationState state = state(session, false);
            if ("CLOSED".equals(state.status())) {
                throw ApiException.conflict("CONVERSATION_CLOSED", "This conversation has ended. Start a new one.");
            }
            escalation.escalate(session.tenantId(), session.conversationId(), EscalationPolicy.customerRequest(), null);
            return view(session);
        });
    }

    /** The customer's rating of a resolved conversation. Accepted once. */
    public ConversationView feedback(WidgetSession session, int rating, String comment) {
        return transactions.execute(status -> {
            tenantContext.bindTenant(session.tenantId());
            ConversationState state = state(session, false);
            if (!"CLOSED".equals(state.status())) {
                throw ApiException.conflict("CONVERSATION_NOT_RESOLVED", "You can rate the conversation once it is resolved.");
            }
            Optional<UUID> inserted = jdbc.sql("""
                            INSERT INTO conversation_feedback (id, tenant_id, conversation_id, rating, comment)
                            VALUES (:id, :tenantId, :conversationId, :rating, :comment)
                            ON CONFLICT ON CONSTRAINT uq_conversation_feedback_conversation DO NOTHING
                            RETURNING id
                            """)
                    .param("id", UUID.randomUUID())
                    .param("tenantId", session.tenantId())
                    .param("conversationId", session.conversationId())
                    .param("rating", rating)
                    .param("comment", comment == null || comment.isBlank() ? null : comment.strip())
                    .query(UUID.class)
                    .optional();
            if (inserted.isEmpty()) {
                throw ApiException.conflict("FEEDBACK_EXISTS", "Thanks, your rating was already recorded.");
            }
            return view(session);
        });
    }

    private ConversationView view(WidgetSession session) {
        ConversationState state = state(session, false);
        boolean rated = jdbc.sql(
                        "SELECT count(*) FROM conversation_feedback WHERE tenant_id = :tenantId AND conversation_id = :conversationId")
                        .param("tenantId", session.tenantId())
                        .param("conversationId", session.conversationId())
                        .query(Long.class)
                        .single()
                > 0;
        return new ConversationView(session.conversationId(), state.status(), state.controlMode(), rated, publicMessages(session));
    }

    private String previousAssistantOutcome(WidgetSession session) {
        return jdbc.sql("""
                        SELECT i.outcome FROM messages m
                        JOIN ai_interactions i ON i.tenant_id = m.tenant_id AND i.id = m.interaction_id
                        WHERE m.tenant_id = :tenantId AND m.conversation_id = :conversationId
                          AND m.sender_type = 'ASSISTANT' AND m.visibility = 'PUBLIC'
                        ORDER BY m.created_at DESC, m.id DESC LIMIT 1
                        """)
                .param("tenantId", session.tenantId())
                .param("conversationId", session.conversationId())
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private Submission duplicate(WidgetSession session, String idempotencyKey, String text, ConversationState state) {
        Map<String, Object> existing = jdbc.sql("""
                        SELECT id, body FROM messages
                        WHERE tenant_id = :tenantId AND conversation_id = :conversationId AND idempotency_key = :key
                        """)
                .param("tenantId", session.tenantId())
                .param("conversationId", session.conversationId())
                .param("key", idempotencyKey)
                .query()
                .singleRow();
        if (!text.equals(existing.get("body"))) {
            throw ApiException.conflict(
                    "IDEMPOTENCY_KEY_REUSED", "This idempotency key was already used for a different message.");
        }
        UUID customerMessageId = (UUID) existing.get("id");
        Optional<UUID> replyId = jdbc.sql("""
                        SELECT id FROM messages
                        WHERE tenant_id = :tenantId AND reply_to_id = :replyTo AND visibility = 'PUBLIC'
                        """)
                .param("tenantId", session.tenantId())
                .param("replyTo", customerMessageId)
                .query(UUID.class)
                .optional();
        MessageView reply = replyId.map(id -> message(session.tenantId(), id)).orElse(null);
        String notice = reply != null ? null : "AI_ACTIVE".equals(state.controlMode()) ? "REPLY_PENDING" : "HUMAN_PENDING";
        return new Submission(message(session.tenantId(), customerMessageId), true, reply, false, notice, state.version(), null);
    }

    private boolean withinTenantBudget(UUID tenantId) {
        try {
            if (rateLimiter.tryAcquire("ai-replies:" + tenantId, properties.aiRepliesPerTenantPerDay(), Duration.ofDays(1))) {
                return true;
            }
            log.warn("Daily AI reply budget reached for tenant {}", tenantId);
        } catch (ApiException e) {
            log.warn("Could not check the AI reply budget for tenant {}", tenantId);
        }
        return false;
    }

    private ConversationState state(WidgetSession session, boolean lock) {
        return jdbc.sql("""
                        SELECT status, control_mode, version FROM conversations
                        WHERE tenant_id = :tenantId AND id = :conversationId AND customer_id = :customerId
                        """ + (lock ? " FOR UPDATE" : ""))
                .param("tenantId", session.tenantId())
                .param("conversationId", session.conversationId())
                .param("customerId", session.customerId())
                .query((rs, n) -> new ConversationState(rs.getString(1), rs.getString(2), rs.getInt(3)))
                .optional()
                .orElseThrow(() -> ApiException.notFound("CONVERSATION_NOT_FOUND", "The conversation was not found."));
    }

    private AssistantView assistant(UUID tenantId) {
        return jdbc.sql("""
                        SELECT id, name, welcome_message, fallback_message, language, tone, automation_enabled,
                               confidence_policy, current_version, updated_at
                        FROM ai_assistants WHERE tenant_id = :tenantId
                        """)
                .param("tenantId", tenantId)
                .query((rs, n) -> new AssistantView(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("welcome_message"),
                        rs.getString("fallback_message"),
                        rs.getString("language"),
                        Tone.valueOf(rs.getString("tone")),
                        rs.getBoolean("automation_enabled"),
                        ConfidencePolicy.valueOf(rs.getString("confidence_policy")),
                        rs.getInt("current_version"),
                        instant(rs, "updated_at")))
                .single();
    }

    private List<MessageView> publicMessages(WidgetSession session) {
        List<MessageView> rows = jdbc.sql("""
                        SELECT id, sender_type, body, created_at FROM (
                            SELECT id, sender_type, body, created_at FROM messages
                            WHERE tenant_id = :tenantId AND conversation_id = :conversationId AND visibility = 'PUBLIC'
                            ORDER BY created_at DESC, id DESC
                            LIMIT :limit
                        ) latest
                        ORDER BY created_at, id
                        """)
                .param("tenantId", session.tenantId())
                .param("conversationId", session.conversationId())
                .param("limit", HISTORY_LIMIT)
                .query((rs, n) -> new MessageView(
                        rs.getObject("id", UUID.class),
                        rs.getString("sender_type"),
                        rs.getString("body"),
                        instant(rs, "created_at"),
                        List.of()))
                .list();
        return withCitations(session.tenantId(), rows);
    }

    private MessageView message(UUID tenantId, UUID messageId) {
        MessageView row = jdbc.sql("SELECT id, sender_type, body, created_at FROM messages WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", messageId)
                .query((rs, n) -> new MessageView(
                        rs.getObject("id", UUID.class),
                        rs.getString("sender_type"),
                        rs.getString("body"),
                        instant(rs, "created_at"),
                        List.of()))
                .single();
        return withCitations(tenantId, List.of(row)).getFirst();
    }

    private List<MessageView> withCitations(UUID tenantId, List<MessageView> messages) {
        if (messages.isEmpty()) {
            return messages;
        }
        Map<UUID, List<CitationView>> byMessage = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT message_id, filename, page_number, heading, snippet FROM message_citations
                        WHERE tenant_id = :tenantId AND message_id IN (:ids)
                        ORDER BY message_id, position
                        """)
                .param("tenantId", tenantId)
                .param("ids", messages.stream().map(MessageView::id).toList())
                .query((rs, n) -> {
                    byMessage.computeIfAbsent(rs.getObject("message_id", UUID.class), id -> new ArrayList<>())
                            .add(new CitationView(
                                    rs.getString("filename"),
                                    (Integer) rs.getObject("page_number"),
                                    rs.getString("heading"),
                                    rs.getString("snippet")));
                    return null;
                })
                .list();
        return messages.stream()
                .map(m -> new MessageView(m.id(), m.senderType(), m.body(), m.createdAt(), byMessage.getOrDefault(m.id(), List.of())))
                .toList();
    }

    private void limit(String bucket, int limit, Duration window) {
        if (!rateLimiter.tryAcquire(bucket, limit, window)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "WIDGET_RATE_LIMITED", "Too many messages. Wait a moment and try again.");
        }
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
