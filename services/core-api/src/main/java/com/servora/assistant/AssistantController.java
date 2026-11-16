package com.servora.assistant;

import com.servora.assistant.AssistantService.AssistantConfig;
import com.servora.assistant.AssistantService.AssistantView;
import com.servora.assistant.AssistantService.ConfidencePolicy;
import com.servora.assistant.AssistantService.Tone;
import com.servora.assistant.AssistantService.VersionView;
import com.servora.common.security.CurrentUser;
import com.servora.common.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/assistant")
public class AssistantController {

    public record UpdateAssistantRequest(
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Size(max = 500) String welcomeMessage,
            @NotBlank @Size(max = 500) String fallbackMessage,
            @NotBlank @Pattern(regexp = "^[a-z]{2}(-[A-Z]{2})?$") String language,
            @NotNull Tone tone,
            @NotNull Boolean automationEnabled,
            @NotNull ConfidencePolicy confidencePolicy) {}

    private final AssistantService assistants;

    public AssistantController(AssistantService assistants) {
        this.assistants = assistants;
    }

    @GetMapping
    AssistantView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId) {
        return assistants.get(CurrentUser.id(jwt), workspaceId);
    }

    /** Replaces the whole configuration and records a new immutable version. */
    @PutMapping
    AssistantView update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID workspaceId,
            @Valid @RequestBody UpdateAssistantRequest request) {
        return assistants.update(
                CurrentUser.id(jwt),
                workspaceId,
                new AssistantConfig(
                        request.name(),
                        request.welcomeMessage(),
                        request.fallbackMessage(),
                        request.language(),
                        request.tone(),
                        request.automationEnabled(),
                        request.confidencePolicy()));
    }

    @GetMapping("/versions")
    PageResponse<VersionView> versions(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID workspaceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return assistants.versions(CurrentUser.id(jwt), workspaceId, page, size);
    }
}
