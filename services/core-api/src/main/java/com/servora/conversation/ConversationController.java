package com.servora.conversation;

import com.servora.common.security.CurrentUser;
import com.servora.common.web.PageResponse;
import com.servora.conversation.ConversationAdminService.AdminMessage;
import com.servora.conversation.ConversationAdminService.ConversationSummary;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/conversations")
public class ConversationController {

    private final ConversationAdminService conversations;

    public ConversationController(ConversationAdminService conversations) {
        this.conversations = conversations;
    }

    @GetMapping
    PageResponse<ConversationSummary> list(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID workspaceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return conversations.list(CurrentUser.id(jwt), workspaceId, page, size);
    }

    @GetMapping("/{conversationId}")
    ConversationSummary get(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId, @PathVariable UUID conversationId) {
        return conversations.get(CurrentUser.id(jwt), workspaceId, conversationId);
    }

    @GetMapping("/{conversationId}/messages")
    PageResponse<AdminMessage> messages(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID workspaceId,
            @PathVariable UUID conversationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return conversations.messages(CurrentUser.id(jwt), workspaceId, conversationId, page, size);
    }
}
