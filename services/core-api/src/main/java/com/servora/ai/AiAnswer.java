package com.servora.ai;

import java.util.List;
import java.util.UUID;

/** What the AI service returned for one question. Field names follow its JSON in snake_case. */
public record AiAnswer(
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
        String embeddingModel,
        double evidenceThreshold,
        int latencyMs,
        int inputTokens,
        int outputTokens) {

    public record Citation(
            UUID chunkId, UUID documentId, String filename, Integer pageNumber, String heading, String snippet) {}

    public record RetrievalHit(UUID chunkId, UUID documentId, String filename, double score, boolean used) {}

    public AiAnswer {
        citations = citations == null ? List.of() : List.copyOf(citations);
        retrieval = retrieval == null ? List.of() : List.copyOf(retrieval);
    }

    public AiAnswer withRetrieval(List<RetrievalHit> hits) {
        return new AiAnswer(
                outcome, answer, intent, needsEscalation, escalationReason, citations, hits, provider, model,
                promptVersion, embeddingModel, evidenceThreshold, latencyMs, inputTokens, outputTokens);
    }
}
