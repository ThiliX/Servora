package com.servora.ai;

import com.servora.usage.UsageService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Appends one row to {@code ai_interactions} for every question put to the assistant. */
@Component
public class InteractionRecorder {

    public enum Source {
        PLAYGROUND,
        WIDGET,
        AGENT_SUGGESTION
    }

    /** Outcomes where no request reached the AI service's model, so nothing is metered. */
    private static final java.util.Set<String> NOT_METERED = java.util.Set.of("OVER_LIMIT", "SERVICE_UNAVAILABLE");

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final UsageService usage;

    public InteractionRecorder(JdbcClient jdbc, ObjectMapper json, UsageService usage) {
        this.jdbc = jdbc;
        this.json = json;
        this.usage = usage;
    }

    /**
     * Must run inside a transaction with {@code tenantId} bound. Returns the new row's id. Also
     * appends the usage event for the request, unless the model was never asked.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID record(
            UUID tenantId, Source source, int assistantVersion, UUID userId, String question, AiAnswer answer) {
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
                        VALUES (:id, :tenantId, :source, :assistantVersion, :userId, :question, :answer,
                            :outcome, :intent, :needsEscalation, :escalationReason, :retrieval::jsonb,
                            :cited::uuid[], :provider, :model, :promptVersion, :embeddingModel, :threshold,
                            :latencyMs, :inputTokens, :outputTokens)
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("source", source.name())
                .param("assistantVersion", assistantVersion)
                .param("userId", userId)
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
        if (!NOT_METERED.contains(answer.outcome())) {
            usage.recordAiRequest(tenantId, source.name(), id, answer.provider(), answer.model(),
                    answer.inputTokens(), answer.outputTokens());
        }
        return id;
    }
}
