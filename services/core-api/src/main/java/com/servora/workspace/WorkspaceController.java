package com.servora.workspace;

import com.servora.common.security.CurrentUser;
import com.servora.workspace.WorkspaceService.Settings;
import com.servora.workspace.WorkspaceService.WorkspaceView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {

    static final String SLUG_PATTERN = "^[a-z0-9][a-z0-9-]{1,48}[a-z0-9]$";
    static final String LANGUAGE_PATTERN = "^[a-z]{2}(-[A-Z]{2})?$";

    public record CreateWorkspaceRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Pattern(regexp = SLUG_PATTERN, message = "must be 3-50 lowercase letters, digits or hyphens")
                    String slug,
            @Size(max = 64) String timezone,
            @Pattern(regexp = LANGUAGE_PATTERN) String defaultLanguage) {}

    public record UpdateWorkspaceRequest(
            @Size(min = 1, max = 100) String name,
            @Size(max = 64) String timezone,
            @Pattern(regexp = LANGUAGE_PATTERN) String defaultLanguage,
            @Size(max = 100) String industry,
            @Size(max = 500) @Pattern(regexp = "^$|^https://\\S+$", message = "must be an https URL") String logoUrl) {}

    private final WorkspaceService workspaces;

    public WorkspaceController(WorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    WorkspaceView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateWorkspaceRequest request) {
        return workspaces.create(
                CurrentUser.id(jwt), request.name(), request.slug(), request.timezone(), request.defaultLanguage());
    }

    @GetMapping
    List<WorkspaceSummary> listMine(@AuthenticationPrincipal Jwt jwt) {
        return workspaces.listMine(CurrentUser.id(jwt));
    }

    @GetMapping("/{workspaceId}")
    WorkspaceView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId) {
        return workspaces.get(CurrentUser.id(jwt), workspaceId);
    }

    @PatchMapping("/{workspaceId}")
    WorkspaceView update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID workspaceId,
            @Valid @RequestBody UpdateWorkspaceRequest request) {
        return workspaces.update(
                CurrentUser.id(jwt),
                workspaceId,
                new Settings(
                        request.name(),
                        request.timezone(),
                        request.defaultLanguage(),
                        request.industry(),
                        request.logoUrl()));
    }
}
