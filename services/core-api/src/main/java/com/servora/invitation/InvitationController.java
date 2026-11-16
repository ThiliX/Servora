package com.servora.invitation;

import com.servora.common.security.CurrentUser;
import com.servora.common.web.PageResponse;
import com.servora.invitation.InvitationService.AcceptedInvitation;
import com.servora.invitation.InvitationService.InvitationView;
import com.servora.membership.WorkspaceRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class InvitationController {

    public record CreateInvitationRequest(
            @NotBlank @Email @Size(max = 254) String email, @NotNull WorkspaceRole role) {}

    public record AcceptInvitationRequest(@NotBlank @Size(max = 200) String token) {}

    private final InvitationService invitations;

    public InvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    @PostMapping("/workspaces/{workspaceId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    InvitationView create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID workspaceId,
            @Valid @RequestBody CreateInvitationRequest request) {
        return invitations.create(CurrentUser.id(jwt), workspaceId, request.email(), request.role());
    }

    @GetMapping("/workspaces/{workspaceId}/invitations")
    PageResponse<InvitationView> list(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID workspaceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return invitations.list(CurrentUser.id(jwt), workspaceId, page, size);
    }

    @DeleteMapping("/workspaces/{workspaceId}/invitations/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId, @PathVariable UUID invitationId) {
        invitations.revoke(CurrentUser.id(jwt), workspaceId, invitationId);
    }

    @PostMapping("/invitations/accept")
    AcceptedInvitation accept(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AcceptInvitationRequest request) {
        return invitations.accept(CurrentUser.id(jwt), request.token());
    }
}
