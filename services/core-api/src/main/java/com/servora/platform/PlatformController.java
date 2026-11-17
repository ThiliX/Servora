package com.servora.platform;

import com.servora.common.security.CurrentUser;
import com.servora.common.web.PageResponse;
import com.servora.platform.PlatformAdminService.PlatformAuditEvent;
import com.servora.platform.PlatformAdminService.TenantRow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform")
public class PlatformController {

    public record Access(boolean platformAdmin) {}

    public record SuspendRequest(@NotBlank @Size(max = 500) String reason) {}

    public record PlanRequest(@NotBlank @Size(max = 32) String planCode) {}

    private final PlatformAdminService platform;

    public PlatformController(PlatformAdminService platform) {
        this.platform = platform;
    }

    /** Lets the dashboard decide whether to show the platform link. Not an authorization control. */
    @GetMapping("/access")
    Access access(@AuthenticationPrincipal Jwt jwt) {
        return new Access(platform.isPlatformAdmin(CurrentUser.id(jwt)));
    }

    @GetMapping("/tenants")
    PageResponse<TenantRow> tenants(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return platform.tenants(CurrentUser.id(jwt), query, page, size);
    }

    @PostMapping("/tenants/{tenantId}/suspend")
    TenantRow suspend(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID tenantId, @Valid @RequestBody SuspendRequest request) {
        return platform.suspend(CurrentUser.id(jwt), tenantId, request.reason().strip());
    }

    @PostMapping("/tenants/{tenantId}/reactivate")
    TenantRow reactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tenantId) {
        return platform.reactivate(CurrentUser.id(jwt), tenantId);
    }

    @PutMapping("/tenants/{tenantId}/plan")
    TenantRow changePlan(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID tenantId, @Valid @RequestBody PlanRequest request) {
        return platform.changePlan(CurrentUser.id(jwt), tenantId, request.planCode().strip());
    }

    @GetMapping("/audit-events")
    PageResponse<PlatformAuditEvent> auditLog(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return platform.auditLog(CurrentUser.id(jwt), page, size);
    }
}
