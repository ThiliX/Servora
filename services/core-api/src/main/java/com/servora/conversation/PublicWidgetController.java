package com.servora.conversation;

import com.servora.common.error.SafeErrors;
import com.servora.common.error.ApiException;
import com.servora.conversation.ConversationService.ConversationView;
import com.servora.conversation.ConversationService.Reply;
import com.servora.conversation.ConversationService.Submission;
import com.servora.conversation.PublicWidgetService.SessionView;
import com.servora.conversation.PublicWidgetService.WidgetConfig;
import com.servora.conversation.WidgetTokens.WidgetSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Endpoints for the public chat widget. Nothing here accepts a dashboard token, and nothing takes
 * a tenant, workspace or conversation id from the request: the widget key names the widget, and
 * after that the session token names the conversation.
 */
@RestController
@RequestMapping("/api/v1/public")
public class PublicWidgetController {

    private static final Logger log = LoggerFactory.getLogger(PublicWidgetController.class);
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9_-]{8,100}$");

    public record MessageRequest(@NotBlank @Size(max = 2000) String text) {}

    public record FeedbackRequest(@NotNull @Min(1) @Max(5) Integer rating, @Size(max = 1000) String comment) {}

    private final PublicWidgetService widgets;
    private final ConversationService conversations;
    private final WidgetTokens tokens;
    private final WidgetProperties properties;
    private final ExecutorService replies;

    public PublicWidgetController(
            PublicWidgetService widgets,
            ConversationService conversations,
            WidgetTokens tokens,
            WidgetProperties properties,
            @Qualifier("widgetReplyExecutor") ExecutorService replies) {
        this.widgets = widgets;
        this.conversations = conversations;
        this.tokens = tokens;
        this.properties = properties;
        this.replies = replies;
    }

    @GetMapping("/widgets/{publicKey}/config")
    WidgetConfig config(
            @PathVariable String publicKey,
            @RequestHeader(value = "Origin", required = false) String origin,
            HttpServletRequest request) {
        return widgets.config(publicKey, origin, request.getRemoteAddr());
    }

    @PostMapping("/widgets/{publicKey}/sessions")
    SessionView createSession(
            @PathVariable String publicKey,
            @RequestHeader(value = "Origin", required = false) String origin,
            HttpServletRequest request) {
        return widgets.createSession(publicKey, origin, request.getRemoteAddr());
    }

    @GetMapping("/conversation")
    ConversationView history(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return conversations.history(session(authorization));
    }

    @PostMapping("/conversation/handoff")
    ConversationView handoff(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return conversations.requestHuman(session(authorization));
    }

    @PostMapping("/conversation/feedback")
    ConversationView feedback(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody FeedbackRequest body) {
        return conversations.feedback(session(authorization), body.rating(), body.comment());
    }

    /**
     * Stores the customer's message, then streams events: {@code customer_message}, then either
     * {@code assistant_typing} and {@code assistant_message}, or a {@code notice}; finally {@code done}.
     */
    @PostMapping("/conversation/messages")
    SseEmitter send(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody MessageRequest body,
            HttpServletRequest request) {
        WidgetSession session = session(authorization);
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw ApiException.badRequest(
                    "IDEMPOTENCY_KEY_REQUIRED", "An Idempotency-Key header of 8 to 100 letters, digits, - or _ is required.");
        }
        Submission submission = conversations.submit(session, idempotencyKey, body.text().strip(), request.getRemoteAddr());
        SseEmitter emitter = new SseEmitter(properties.replyTimeout().toMillis() + 15_000);
        replies.execute(() -> stream(emitter, session, submission));
        return emitter;
    }

    private void stream(SseEmitter emitter, WidgetSession session, Submission submission) {
        Events events = new Events(emitter);
        try {
            events.send("customer_message", submission.customerMessage());
            if (submission.existingReply() != null) {
                events.send("assistant_message", submission.existingReply());
            }
            if (submission.systemMessage() != null) {
                events.send("system_message", submission.systemMessage());
            }
            if (submission.notice() != null) {
                events.send("notice", Map.of("code", submission.notice()));
            }
            if (submission.replyExpected()) {
                events.send("assistant_typing", Map.of());
                // Runs to completion even if the customer has gone: the reply is then in the history.
                Reply reply = conversations.generateReply(session, submission);
                if (reply.delivered()) {
                    events.send("assistant_message", reply.message());
                    if (reply.systemMessage() != null) {
                        events.send("system_message", reply.systemMessage());
                        events.send("notice", Map.of("code", "HUMAN_PENDING"));
                    }
                } else {
                    events.send("notice", Map.of("code", "SUPPRESSED"));
                }
            }
            events.send("done", Map.of());
        } catch (RuntimeException e) {
            log.error("Widget reply failed for conversation {}: {}", session.conversationId(), SafeErrors.describe(e));
            events.send("error", Map.of("code", "REPLY_FAILED"));
        } finally {
            events.complete();
        }
    }

    private WidgetSession session(String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()).trim()
                : null;
        return tokens.verify(token)
                .orElseThrow(() -> ApiException.unauthorized(
                        "WIDGET_SESSION_INVALID", "The chat session is missing or has expired. Start a new chat."));
    }

    /** Sends events until the client goes away, then quietly stops sending. */
    private static final class Events {

        private final SseEmitter emitter;
        private boolean open = true;

        Events(SseEmitter emitter) {
            this.emitter = emitter;
        }

        void send(String name, Object data) {
            if (!open) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                open = false;
            }
        }

        void complete() {
            if (open) {
                try {
                    emitter.complete();
                } catch (IllegalStateException ignored) {
                    // Already completed or timed out.
                }
            }
        }
    }
}
