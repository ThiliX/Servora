package com.servora.ai;

import com.servora.ai.AiAnswer.Citation;
import com.servora.ai.AiAnswer.RetrievalHit;
import com.servora.ai.AiClient.AiProperties;
import com.servora.ai.AiClient.AiUnavailableException;
import com.servora.ai.InteractionRecorder.Source;
import com.servora.assistant.AssistantService;
import com.servora.assistant.AssistantService.AssistantView;
import com.servora.common.error.ApiException;
import com.servora.common.ratelimit.RateLimiter;
import com.servora.membership.Permission;
import com.servora.membership.WorkspaceAccess;
import com.servora.membership.WorkspaceContext;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The private assistant playground: a workspace admin asks a question and sees the answer, its
 * citations and what was retrieved. The reply is checked by {@link AnswerVerifier} before it is
 * returned or stored.
 */
@Service
public class PlaygroundService {

    private static final Logger log = LoggerFactory.getLogger(PlaygroundService.class);

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

    private final WorkspaceAccess access;
    private final AssistantService assistants;
    private final AiClient ai;
    private final AnswerVerifier verifier;
    private final InteractionRecorder recorder;
    private final RateLimiter rateLimiter;
    private final AiProperties properties;
    private final TransactionTemplate transactions;

    public PlaygroundService(
            WorkspaceAccess access,
            AssistantService assistants,
            AiClient ai,
            AnswerVerifier verifier,
            InteractionRecorder recorder,
            RateLimiter rateLimiter,
            AiProperties properties,
            PlatformTransactionManager transactionManager) {
        this.access = access;
        this.assistants = assistants;
        this.ai = ai;
        this.verifier = verifier;
        this.recorder = recorder;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public PlaygroundResult ask(UUID userId, UUID workspaceId, String question) {
        // Authorises and reads the configuration in its own short transaction.
        AssistantView assistant = assistants.get(userId, workspaceId);
        if (!rateLimiter.tryAcquire("playground:" + workspaceId, properties.testsPerHour(), Duration.ofHours(1))) {
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
            answer = null;
        }
        AiAnswer received = AnswerVerifier.normalise(answer, assistant.fallbackMessage());

        return transactions.execute(status -> {
            // Authorise again: membership may have changed while the model was answering.
            WorkspaceContext ctx = access.authorize(userId, workspaceId, Permission.CONFIGURE_AI);
            AiAnswer verified = verifier.verify(ctx.tenantId(), assistant.fallbackMessage(), received);
            UUID id = recorder.record(
                    ctx.tenantId(), Source.PLAYGROUND, assistant.currentVersion(), ctx.userId(), question, verified);
            return new PlaygroundResult(
                    id,
                    verified.outcome(),
                    verified.answer(),
                    verified.intent(),
                    verified.needsEscalation(),
                    verified.escalationReason(),
                    verified.citations(),
                    verified.retrieval(),
                    verified.provider(),
                    verified.model(),
                    verified.promptVersion(),
                    verified.evidenceThreshold(),
                    verified.latencyMs(),
                    verified.inputTokens(),
                    verified.outputTokens());
        });
    }
}
