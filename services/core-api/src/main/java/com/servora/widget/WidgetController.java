package com.servora.widget;

import com.servora.common.security.CurrentUser;
import com.servora.widget.WidgetService.Position;
import com.servora.widget.WidgetService.Status;
import com.servora.widget.WidgetService.WidgetSettings;
import com.servora.widget.WidgetService.WidgetView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/widget")
public class WidgetController {

    public record UpdateWidgetRequest(
            @Size(min = 1, max = 80) String name,
            @Size(min = 1, max = 80) String title,
            @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "must be a hex colour such as #4f46e5") String themeColor,
            Position position,
            Boolean showBranding,
            Status status) {}

    public record ReplaceOriginsRequest(@NotNull @Size(max = 50) List<@Size(max = 300) String> origins) {}

    private final WidgetService widgets;

    public WidgetController(WidgetService widgets) {
        this.widgets = widgets;
    }

    @GetMapping
    WidgetView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId) {
        return widgets.get(CurrentUser.id(jwt), workspaceId);
    }

    @PatchMapping
    WidgetView update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID workspaceId,
            @Valid @RequestBody UpdateWidgetRequest request) {
        return widgets.update(
                CurrentUser.id(jwt),
                workspaceId,
                new WidgetSettings(
                        request.name(),
                        request.title(),
                        request.themeColor(),
                        request.position(),
                        request.showBranding(),
                        request.status()));
    }

    @PostMapping("/rotate-key")
    WidgetView rotateKey(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId) {
        return widgets.rotateKey(CurrentUser.id(jwt), workspaceId);
    }

    @PutMapping("/allowed-origins")
    WidgetView replaceAllowedOrigins(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID workspaceId,
            @Valid @RequestBody ReplaceOriginsRequest request) {
        return widgets.replaceAllowedOrigins(CurrentUser.id(jwt), workspaceId, request.origins());
    }
}
