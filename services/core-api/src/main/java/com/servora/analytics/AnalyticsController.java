package com.servora.analytics;

import com.servora.analytics.AnalyticsService.Overview;
import com.servora.common.security.CurrentUser;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AnalyticsController {

    private final AnalyticsService analytics;

    public AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/api/v1/workspaces/{workspaceId}/analytics")
    Overview overview(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId, @RequestParam(defaultValue = "30") int days) {
        return analytics.overview(CurrentUser.id(jwt), workspaceId, days);
    }
}
