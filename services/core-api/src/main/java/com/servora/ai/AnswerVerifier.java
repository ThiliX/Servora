package com.servora.ai;

import com.servora.ai.AiAnswer.RetrievalHit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The core API's own check of an AI service reply, applied before anything is shown or stored.
 *
 * <p>Every chunk the reply names must exist in the caller's tenant, and every citation must be one
 * of the chunks the reply says were given to the model. A reply that fails is replaced by the
 * assistant's fallback message.
 */
@Component
public class AnswerVerifier {

    private static final Logger log = LoggerFactory.getLogger(AnswerVerifier.class);

    public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";
    /** The workspace has used its plan's AI requests or tokens for the month; the model was not asked. */
    public static final String OVER_LIMIT = "OVER_LIMIT";
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
    public static final Set<String> MODEL_TEXT_OUTCOMES = Set.of("ANSWERED", "ESCALATED");

    private final JdbcClient jdbc;

    public AnswerVerifier(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Replaces a missing or unrecognised reply with the fallback. Needs no database access. */
    public static AiAnswer normalise(AiAnswer answer, String fallbackMessage) {
        if (answer == null || answer.answer() == null || !OUTCOMES.contains(answer.outcome())) {
            return fallback(fallbackMessage, SERVICE_UNAVAILABLE, "ai_service_unavailable", null);
        }
        return answer;
    }

    /** Must run inside a transaction with {@code tenantId} bound for Row-Level Security. */
    @Transactional(propagation = Propagation.MANDATORY)
    public AiAnswer verify(UUID tenantId, String fallbackMessage, AiAnswer answer) {
        if (SERVICE_UNAVAILABLE.equals(answer.outcome()) || OVER_LIMIT.equals(answer.outcome())) {
            return answer;
        }
        Set<UUID> givenToModel = answer.retrieval().stream()
                .filter(RetrievalHit::used)
                .map(RetrievalHit::chunkId)
                .collect(Collectors.toSet());
        Set<UUID> named = new HashSet<>();
        answer.retrieval().forEach(hit -> named.add(hit.chunkId()));
        answer.citations().forEach(citation -> named.add(citation.chunkId()));

        if (!named.isEmpty()) {
            long owned = jdbc.sql("SELECT count(*) FROM document_chunks WHERE tenant_id = :tenantId AND id IN (:ids)")
                    .param("tenantId", tenantId)
                    .param("ids", named)
                    .query(Long.class)
                    .single();
            if (owned != named.size()) {
                log.warn("AI service named chunks outside tenant {}; response discarded", tenantId);
                return fallback(fallbackMessage, "INVALID_CITATION", "invalid_citation", answer);
            }
        }
        boolean citedWereGiven = answer.citations().stream().allMatch(c -> givenToModel.contains(c.chunkId()));
        boolean answeredWithoutCitation =
                "ANSWERED".equals(answer.outcome()) && answer.citations().isEmpty();
        boolean fallbackCarriesOtherText = !MODEL_TEXT_OUTCOMES.contains(answer.outcome())
                && !answer.answer().equals(fallbackMessage);
        if (!citedWereGiven || answeredWithoutCitation || fallbackCarriesOtherText) {
            return fallback(fallbackMessage, "INVALID_CITATION", "invalid_citation", answer)
                    .withRetrieval(answer.retrieval());
        }
        return answer;
    }

    /** The assistant's own fallback message, asking for a person. Keeps the metadata of {@code source}, if any. */
    public static AiAnswer fallback(String fallbackMessage, String outcome, String reason, AiAnswer source) {
        boolean none = source == null;
        return new AiAnswer(
                outcome,
                fallbackMessage,
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
