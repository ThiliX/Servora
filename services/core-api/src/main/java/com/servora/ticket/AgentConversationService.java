package com.servora.ticket;

import static com.servora.conversation.ConversationService.instant;

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
import com.servora.membership.WorkspaceContext;
import com.servora.ticket.StaffAccess.Target;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What an agent writes in a conversation: replies the customer sees, internal notes the customer
 * never sees, and AI suggestions that reach the customer only if an agent sends them.
 */
@Service
public class AgentConversationService {

    private static final Logger log = LoggerFactory.getLogger(AgentConversationService.class);

    public record PostedMessage(UUID id, Instant createdAt) {}

    public record NoteView(UUID id, String authorName, String body, Instant createdAt) {}

    public record SuggestionView(UUID id, String outcome, String body, boolean usable, List<Citation> citations) {}

    private final JdbcClient jdbc;
    private final StaffAccess staff;
    private final TicketService tickets;
    private final AiClient ai;
    private final AnswerVerifier verifier;
    private final InteractionRecorder recorder;
    private final TransactionTemplate transactions;

    public AgentConversationService(
            JdbcClient jdbc,
            StaffAccess staff,
            TicketService tickets,
            AiClient ai,
            AnswerVerifier verifier,
            InteractionRecorder recorder,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.staff = staff;
        this.tickets = tickets;
        this.ai = ai;
        this.verifier = verifier;
        this.recorder = recorder;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Sends a reply to the customer. Only the ticket's assignee may reply, and only after taking the
     * conversation over, so the assistant and a person never answer at the same time.
     */
    public PostedMessage reply(UUID userId, UUID workspaceId, UUID conversationId, String body, UUID suggestionId) {
        return transactions.execute(status -> {
            WorkspaceContext ctx = staff.authorize(userId, workspaceId);
            Target target = staff.requireConversation(ctx, conversationId);
            Map<String, Object> conversation = lockConversation(ctx, conversationId);
            if (!"OPEN".equals(conversation.get("status"))) {
                throw ApiException.conflict("CONVERSATION_CLOSED", "This conversation has ended.");
            }
            if (!"HUMAN_ACTIVE".equals(conversation.get("control_mode")) || !target.assignedTo(ctx.membershipId())) {
                throw ApiException.conflict("TAKEOVER_REQUIRED", "Take over the conversation before replying.");
            }
            if (suggestionId != null) {
                long found = jdbc.sql("""
                                SELECT count(*) FROM messages
                                WHERE tenant_id = :tenantId AND id = :id AND conversation_id = :conversationId
                                  AND visibility = 'AI_SUGGESTION'
                                """)
                        .param("tenantId", ctx.tenantId())
                        .param("id", suggestionId)
                        .param("conversationId", conversationId)
                        .query(Long.class)
                        .single();
                if (found != 1) {
                    throw ApiException.notFound("SUGGESTION_NOT_FOUND", "The suggestion was not found.");
                }
            }
            UUID id = UUID.randomUUID();
            Instant createdAt = jdbc.sql("""
                            INSERT INTO messages (id, tenant_id, conversation_id, sender_type, body, author_membership_id,
                                                  source_suggestion_id)
                            VALUES (:id, :tenantId, :conversationId, 'AGENT', :body, :author, :suggestion)
                            RETURNING created_at
                            """)
                    .param("id", id)
                    .param("tenantId", ctx.tenantId())
                    .param("conversationId", conversationId)
                    .param("body", body)
                    .param("author", ctx.membershipId())
                    .param("suggestion", suggestionId)
                    .query((rs, n) -> instant(rs, "created_at"))
                    .single();
            jdbc.sql("""
                            UPDATE conversations SET last_message_at = now(), updated_at = now()
                            WHERE tenant_id = :tenantId AND id = :conversationId
                            """)
                    .param("tenantId", ctx.tenantId())
                    .param("conversationId", conversationId)
                    .update();
            tickets.onAgentReply(ctx, target.ticketId());
            return new PostedMessage(id, createdAt);
        });
    }

    public NoteView addNote(UUID userId, UUID workspaceId, UUID conversationId, String body) {
        return transactions.execute(status -> {
            WorkspaceContext ctx = staff.authorize(userId, workspaceId);
            staff.requireConversation(ctx, conversationId);
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                            INSERT INTO internal_notes (id, tenant_id, conversation_id, author_membership_id, body)
                            VALUES (:id, :tenantId, :conversationId, :author, :body)
                            """)
                    .param("id", id)
                    .param("tenantId", ctx.tenantId())
                    .param("conversationId", conversationId)
                    .param("author", ctx.membershipId())
                    .param("body", body)
                    .update();
            return notes(ctx, conversationId).stream().filter(n -> n.id().equals(id)).findFirst().orElseThrow();
        });
    }

    public List<NoteView> notes(UUID userId, UUID workspaceId, UUID conversationId) {
        return transactions.execute(status -> {
            WorkspaceContext ctx = staff.authorize(userId, workspaceId);
            staff.requireConversation(ctx, conversationId);
            return notes(ctx, conversationId);
        });
    }

    /**
     * Asks the assistant to draft a reply to the customer's latest message. The draft is checked like
     * any AI answer and stored for the team only; it is never shown to the customer.
     */
    public SuggestionView suggest(UUID userId, UUID workspaceId, UUID conversationId) {
        record Context(UUID tenantId, AssistantView assistant, String question) {}
        Context context = transactions.execute(status -> {
            WorkspaceContext ctx = staff.authorize(userId, workspaceId);
            staff.requireConversation(ctx, conversationId);
            String question = jdbc.sql("""
                            SELECT body FROM messages
                            WHERE tenant_id = :tenantId AND conversation_id = :conversationId AND sender_type = 'CUSTOMER'
                            ORDER BY created_at DESC, id DESC LIMIT 1
                            """)
                    .param("tenantId", ctx.tenantId())
                    .param("conversationId", conversationId)
                    .query(String.class)
                    .optional()
                    .orElseThrow(() -> ApiException.conflict("NOTHING_TO_ANSWER", "The customer has not asked anything yet."));
            return new Context(ctx.tenantId(), assistant(ctx.tenantId()), question);
        });

        AiAnswer answer = null;
        try {
            answer = ai.answer(context.tenantId(), context.assistant(), context.question());
        } catch (AiUnavailableException e) {
            log.warn("AI service unavailable for a suggestion: {}", e.getMessage());
        }
        AiAnswer received = AnswerVerifier.normalise(answer, context.assistant().fallbackMessage());

        return transactions.execute(status -> {
            WorkspaceContext ctx = staff.authorize(userId, workspaceId);
            staff.requireConversation(ctx, conversationId);
            AiAnswer verified = verifier.verify(ctx.tenantId(), context.assistant().fallbackMessage(), received);
            UUID interactionId = recorder.record(ctx.tenantId(), Source.AGENT_SUGGESTION,
                    context.assistant().currentVersion(), ctx.userId(), context.question(), verified);
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                            INSERT INTO messages (id, tenant_id, conversation_id, sender_type, visibility, delivery_status,
                                                  body, interaction_id)
                            VALUES (:id, :tenantId, :conversationId, 'ASSISTANT', 'AI_SUGGESTION', 'PENDING_APPROVAL',
                                    :body, :interactionId)
                            """)
                    .param("id", id)
                    .param("tenantId", ctx.tenantId())
                    .param("conversationId", conversationId)
                    .param("body", verified.answer())
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
                        .param("tenantId", ctx.tenantId())
                        .param("messageId", id)
                        .param("position", position++)
                        .param("chunkId", citation.chunkId())
                        .param("documentId", citation.documentId())
                        .param("filename", citation.filename())
                        .param("pageNumber", citation.pageNumber())
                        .param("heading", citation.heading())
                        .param("snippet", citation.snippet() == null ? "" : citation.snippet())
                        .update();
            }
            boolean usable = AnswerVerifier.MODEL_TEXT_OUTCOMES.contains(verified.outcome());
            return new SuggestionView(id, verified.outcome(), verified.answer(), usable, verified.citations());
        });
    }

    private List<NoteView> notes(WorkspaceContext ctx, UUID conversationId) {
        return jdbc.sql("""
                        SELECT n.id, u.display_name, n.body, n.created_at
                        FROM internal_notes n
                        JOIN memberships m ON m.tenant_id = n.tenant_id AND m.id = n.author_membership_id
                        JOIN users u ON u.id = m.user_id
                        WHERE n.tenant_id = :tenantId AND n.conversation_id = :conversationId
                        ORDER BY n.created_at, n.id
                        """)
                .param("tenantId", ctx.tenantId())
                .param("conversationId", conversationId)
                .query((rs, n) -> new NoteView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), instant(rs, "created_at")))
                .list();
    }

    private Map<String, Object> lockConversation(WorkspaceContext ctx, UUID conversationId) {
        return jdbc.sql("""
                        SELECT status, control_mode FROM conversations
                        WHERE tenant_id = :tenantId AND id = :conversationId FOR UPDATE
                        """)
                .param("tenantId", ctx.tenantId())
                .param("conversationId", conversationId)
                .query()
                .singleRow();
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
}
