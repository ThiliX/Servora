package com.servora.ai;

import com.servora.ai.AiAnswer.Citation;
import com.servora.ai.AiAnswer.RetrievalHit;
import com.servora.ai.AiClient.AiUnavailableException;
import com.servora.assistant.AssistantService;
import com.servora.assistant.AssistantService.AssistantView;
import com.servora.common.error.ApiException;
import com.servora.membership.Permission;
import com.servora.membership.WorkspaceAccess;
import com.servora.membership.WorkspaceContext;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The private assistant playground: a workspace admin asks a question and sees the answer, its
 * citations and what was retrieved.
 *
 * <p>The AI service is not trusted blindly. Before anything is returned or stored, every chunk it
 * names is checked again here, under Row-Level Security, to belong to this workspace, and every
 * citation must be one of the chunks it said it gave the model.
 */
@Service
public class PlaygroundService {

    private static final Logger log = LoggerFactory.getLogger(PlaygroundService.class);

    private static final String UNAVAILABLE = "SERVICE_UNAVAILABLE";
    private static final Set<String> OUTCOMES = Set.of(
            "ANSWERED",
            "ESCALATED",
            "NO_EVIDENCE",
            "PROVIDER_UNAVAILABLE",
            "INVALID_OUTPUT",
            "INVALID_CITATION",
            "UNGROUNDED",
            "REFUSED");
    /** Outcomes whose answer text was written by the model rather than being the fallback message. */
    private static final Set<String> MODEL_TEXT_OUTCOMES = Set.of("ANSWERED", "ESCALATED");

    public record PlaygroundResult(
            UUID id,
            String outcome,
            String answer,
            String intent,
            boolean needsEscalation,
            String escalationReason,
            List<Citation> citations,
            List<RetrievalHit> retrieval,
            String provider,
            String model,
            String promptVersion,
            double evidenceThreshold,
            int latencyMs,
            int inputTokens,
            int outputTokens) {}

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final WorkspaceAccess access;
    private final AssistantService assistants;
    private final AiClient ai;
    private final PlaygroundRateLimiter rateLimiter;
    private final TransactionTemplate transactions;

    public PlaygroundService(
            JdbcClient jdbc,
            ObjectMapper json,
            WorkspaceAccess access,
            AssistantService assistants,
            AiClient ai,
            PlaygroundRateLimiter rateLimiter,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.json = json;
        this.access = access;
        this.assistants = assistants;
        this.ai = ai;
        this.rateLimiter = rateLimiter;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public PlaygroundResult ask(UUID userId, UUID workspaceId, String question) {
        // Authorises and reads the configuration in its own short transaction.
        AssistantView assistant = assistants.get(userId, workspaceId);
        if (!rateLimiter.tryAcquire(workspaceId)) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "PLAYGROUND_RATE_LIMITED",
                    "Too many test questions this hour. Try again later.");
        }

        // The model call can take seconds, so no database connection is held while it runs.
        AiAnswer answer;
        try {
            answer = ai.answer(workspaceId, assistant, question);
        } catch (AiUnavailableException e) {
            log.warn("AI service unavailable: {}", e.getMessage());
            answer = fallback(assistant, UNAVAILABLE, "ai_service_unavailable", null);
        }
        if (answer == null || answer.answer() == null || !OUTCOMES.contains(answer.outcome())) {
            if (answer == null || !UNAVAILABLE.equals(answer.outcome())) {
                answer = fallback(assistant, UNAVAILABLE, "ai_service_unavailable", null);
            }
        }

        AiAnswer received = answer;
        return transactions.execute(status -> {
            // Authorise again: membership may have changed while the model was answering.
            WorkspaceContext ctx = access.authorize(userId, workspaceId, Permission.CONFIGURE_AI);
            return store(ctx, assistant, question, verify(ctx, assistant, received));
        });
    }

    private AiAnswer verify(WorkspaceContext ctx, AssistantView assistant, AiAnswer answer) {
        Set<UUID> givenToModel = answer.retrieval().stream()
                .filter(RetrievalHit::used)
                .map(RetrievalHit::chunkId)
                .collect(Collectors.toSet());
        Set<UUID> named = new HashSet<>();
        answer.retrieval().forEach(hit -> named.add(hit.chunkId()));
        answer.citations().forEach(citation -> named.add(citation.chunkId()));

        if (!named.isEmpty()) {
            long owned = jdbc.sql("SELECT count(*) FROM document_chunks WHERE tenant_id = :tenantId AND id IN (:ids)")
                    .param("tenantId", ctx.tenantId())
                    .param("ids", named)
                    .query(Long.class)
                    .single();
            if (owned != named.size()) {
                log.warn("AI service named chunks outside tenant {}; response discarded", ctx.tenantId());
                return fallback(assistant, "INVALID_CITATION", "invalid_citation", answer);
            }
        }
        boolean citedWereGiven = answer.citations().stream().allMatch(c -> givenToModel.contains(c.chunkId()));
        boolean answeredWithoutCitation =
                "ANSWERED".equals(answer.outcome()) && answer.citations().isEmpty();
        boolean fallbackCarriesOtherText = !MODEL_TEXT_OUTCOMES.contains(answer.outcome())
                && !answer.answer().equals(assistant.fallbackMessage());
        if (!citedWereGiven || answeredWithoutCitation || fallbackCarriesOtherText) {
            return fallback(assistant, "INVALID_CITATION", "invalid_citation", answer)
                    .withRetrieval(answer.retrieval());
        }
        return answer;
    }

    private PlaygroundResult store(WorkspaceContext ctx, AssistantView assistant, String question, AiAnswer answer) {
        UUID id = UUID.randomUUID();
        List<Map<String, Object>> retrieval = answer.retrieval().stream()
                .map(hit -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("chunk_id", hit.chunkId().toString());
                    row.put("document_id", String.valueOf(hit.documentId()));
                    row.put("score", hit.score());
                    row.put("used", hit.used());
                    return row;
                })
                .toList();
        jdbc.sql("""
                        INSERT INTO ai_interactions (id, tenant_id, source, assistant_version, created_by_user_id,
                            question, answer, outcome, intent, needs_escalation, escalation_reason, retrieval,
                            cited_chunk_ids, provider, model, prompt_version, embedding_model, evidence_threshold,
                            latency_ms, input_tokens, output_tokens)
                        VALUES (:id, :tenantId, 'PLAYGROUND', :assistantVersion, :userId, :question, :answer,
                            :outcome, :intent, :needsEscalation, :escalationReason, :retrieval::jsonb,
                            :cited::uuid[], :provider, :model, :promptVersion, :embeddingModel, :threshold,
                            :latencyMs, :inputTokens, :outputTokens)
                        """)
                .param("id", id)
                .param("tenantId", ctx.tenantId())
                .param("assistantVersion", assistant.currentVersion())
                .param("userId", ctx.userId())
                .param("question", question)
                .param("answer", answer.answer())
                .param("outcome", answer.outcome())
                .param("intent", answer.intent())
                .param("needsEscalation", answer.needsEscalation())
                .param("escalationReason", answer.escalationReason())
                .param("retrieval", json.writeValueAsString(retrieval))
                .param(
                        "cited",
                        answer.citations().stream()
                                .map(c -> c.chunkId().toString())
                                .collect(Collectors.joining(",", "{", "}")))
                .param("provider", answer.provider())
                .param("model", answer.model())
                .param("promptVersion", answer.promptVersion())
                .param("embeddingModel", answer.embeddingModel())
                .param("threshold", answer.evidenceThreshold())
                .param("latencyMs", answer.latencyMs())
                .param("inputTokens", answer.inputTokens())
                .param("outputTokens", answer.outputTokens())
                .update();
        return new PlaygroundResult(
                id,
                answer.outcome(),
                answer.answer(),
                answer.intent(),
                answer.needsEscalation(),
                answer.escalationReason(),
                answer.citations(),
                answer.retrieval(),
                answer.provider(),
                answer.model(),
                answer.promptVersion(),
                answer.evidenceThreshold(),
                answer.latencyMs(),
                answer.inputTokens(),
                answer.outputTokens());
    }

    /** The assistant's own fallback message, asking for a person. Keeps the metadata of {@code source}, if any. */
    private static AiAnswer fallback(AssistantView assistant, String outcome, String reason, AiAnswer source) {
        boolean none = source == null;
        return new AiAnswer(
                outcome,
                assistant.fallbackMessage(),
                null,
                true,
                reason,
                List.of(),
                List.of(),
                none || source.provider() == null ? "none" : source.provider(),
                none ? null : source.model(),
                none || source.promptVersion() == null ? "none" : source.promptVersion(),
                none || source.embeddingModel() == null ? "none" : source.embeddingModel(),
                none ? 0 : source.evidenceThreshold(),
                none ? 0 : source.latencyMs(),
                none ? 0 : source.inputTokens(),
                none ? 0 : source.outputTokens());
    }
}
