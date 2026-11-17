package com.servora.ai;

import com.servora.ai.PlaygroundService.PlaygroundResult;
import com.servora.common.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/assistant")
public class PlaygroundController {

    public record TestRequest(@NotBlank @Size(max = 2000) String question) {}

    private final PlaygroundService playground;

    public PlaygroundController(PlaygroundService playground) {
        this.playground = playground;
    }

    /** Asks the assistant a question privately, to try it before customers do. */
    @PostMapping("/test")
    PlaygroundResult test(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId, @Valid @RequestBody TestRequest request) {
        return playground.ask(CurrentUser.id(jwt), workspaceId, request.question().trim());
    }
}
