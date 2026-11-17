package com.servora.ticket;

import com.servora.common.security.CurrentUser;
import com.servora.common.web.PageResponse;
import com.servora.ticket.AgentConversationService.NoteView;
import com.servora.ticket.AgentConversationService.PostedMessage;
import com.servora.ticket.AgentConversationService.SuggestionView;
import com.servora.ticket.NotificationService.NotificationList;
import com.servora.ticket.TicketService.Assignee;
import com.servora.ticket.TicketService.Outcome;
import com.servora.ticket.TicketService.State;
import com.servora.ticket.TicketService.TicketDetail;
import com.servora.ticket.TicketService.TicketSummary;
import com.servora.ticket.TicketService.View;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The agent workspace: tickets, takeover, replies, notes, suggestions and notifications. */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class TicketController {

    public record AssignRequest(UUID membershipId) {}

    public record UpdateRequest(TicketStatus status, EscalationPolicy.Priority priority) {}

    public record ResolveRequest(@NotNull Outcome outcome, @Size(max = 2000) String note) {}

    public record TakeoverRequest(@NotNull Integer expectedVersion) {}

    public record TextRequest(@NotBlank @Size(max = 4000) String body) {}

    public record ReplyRequest(@NotBlank @Size(max = 4000) String body, UUID suggestionId) {}

    public record ReadRequest(List<UUID> ids) {}

    private final TicketService tickets;
    private final AgentConversationService conversations;
    private final NotificationService notifications;

    public TicketController(
            TicketService tickets, AgentConversationService conversations, NotificationService notifications) {
        this.tickets = tickets;
        this.conversations = conversations;
        this.notifications = notifications;
    }

    @GetMapping("/tickets")
    PageResponse<TicketSummary> list(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID workspaceId,
            @RequestParam(defaultValue = "ALL") View view,
            @RequestParam(defaultValue = "ACTIVE") State state,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return tickets.list(CurrentUser.id(jwt), workspaceId, view, state, page, size);
    }

    @GetMapping("/tickets/assignees")
    List<Assignee> assignees(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId) {
        return tickets.assignees(CurrentUser.id(jwt), workspaceId);
    }

    @GetMapping("/tickets/{ticketId}")
    TicketDetail get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId, @PathVariable UUID ticketId) {
        return tickets.detail(CurrentUser.id(jwt), workspaceId, ticketId);
    }

    @PutMapping("/tickets/{ticketId}/assignee")
    TicketDetail assign(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID workspaceId,
            @PathVariable UUID ticketId,
            @RequestBody AssignRequest request) {
        return tickets.assign(CurrentUser.id(jwt), workspaceId, ticketId, request.membershipId());
    }

    @PatchMapping("/tickets/{ticketId}")
    TicketDetail update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID workspaceId,
            @PathVariable UUID ticketId,
            @RequestBody UpdateRequest request) {
        return tickets.update(CurrentUser.id(jwt), workspaceId, ticketId, request.status(), request.priority());
    }

    @PostMapping("/tickets/{ticketId}/resolve")
    TicketDetail resolve(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID workspaceId,
            @PathVariable UUID ticketId,
            @Valid @RequestBody ResolveRequest request) {
        return tickets.resolve(CurrentUser.id(jwt), workspaceId, ticketId, request.outcome(), request.note());
    }

    @PostMapping("/tickets/{ticketId}/close")
    TicketDetail close(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId, @PathVariable UUID ticketId) {
        return tickets.close(CurrentUser.id(jwt), workspaceId, ticketId);
    }

    @PostMapping("/conversations/{conversationId}/takeover")
    TicketDetail takeover(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID workspaceId,
            @PathVariable UUID conversationId,
            @Valid @RequestBody TakeoverRequest request) {
        return tickets.takeover(CurrentUser.id(jwt), workspaceId, conversationId, request.expectedVersion());
    }

    @PostMapping("/conversations/{conversationId}/replies")
    @ResponseStatus(HttpStatus.CREATED)
    PostedMessage reply(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID workspaceId,
            @PathVariable UUID conversationId,
            @Valid @RequestBody ReplyRequest request) {
        return conversations.reply(CurrentUser.id(jwt), workspaceId, conversationId, request.body().strip(), request.suggestionId());
    }

    @GetMapping("/conversations/{conversationId}/notes")
    List<NoteView> notes(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId, @PathVariable UUID conversationId) {
        return conversations.notes(CurrentUser.id(jwt), workspaceId, conversationId);
    }

    @PostMapping("/conversations/{conversationId}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    NoteView addNote(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID workspaceId,
            @PathVariable UUID conversationId,
            @Valid @RequestBody TextRequest request) {
        return conversations.addNote(CurrentUser.id(jwt), workspaceId, conversationId, request.body().strip());
    }

    @PostMapping("/conversations/{conversationId}/suggestions")
    @ResponseStatus(HttpStatus.CREATED)
    SuggestionView suggest(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId, @PathVariable UUID conversationId) {
        return conversations.suggest(CurrentUser.id(jwt), workspaceId, conversationId);
    }

    @GetMapping("/notifications")
    NotificationList notifications(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId) {
        return notifications.mine(CurrentUser.id(jwt), workspaceId);
    }

    @PostMapping("/notifications/read")
    NotificationList markRead(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId, @RequestBody(required = false) ReadRequest request) {
        return notifications.markRead(CurrentUser.id(jwt), workspaceId, request == null ? List.of() : request.ids());
    }
}
