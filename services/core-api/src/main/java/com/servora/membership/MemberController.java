package com.servora.membership;

import com.servora.common.security.CurrentUser;
import com.servora.common.web.PageResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/members")
public class MemberController {

    public record UpdateMemberRequest(WorkspaceRole role, Membership.Status status) {}

    private final MemberService members;

    public MemberController(MemberService members) {
        this.members = members;
    }

    @GetMapping
    PageResponse<MemberView> list(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID workspaceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return members.list(CurrentUser.id(jwt), workspaceId, page, size);
    }

    @PatchMapping("/{memberId}")
    MemberView update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID workspaceId,
            @PathVariable UUID memberId,
            @Valid @RequestBody UpdateMemberRequest request) {
        return members.update(CurrentUser.id(jwt), workspaceId, memberId, request.role(), request.status());
    }
}
