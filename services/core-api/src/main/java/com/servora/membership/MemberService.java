package com.servora.membership;

import com.servora.audit.AuditService;
import com.servora.common.error.ApiException;
import com.servora.common.web.PageResponse;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberService {

    private final WorkspaceAccess access;
    private final MembershipRepository memberships;
    private final AuditService audit;
    private final Clock clock;

    public MemberService(WorkspaceAccess access, MembershipRepository memberships, AuditService audit, Clock clock) {
        this.access = access;
        this.memberships = memberships;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<MemberView> list(UUID userId, UUID workspaceId, int page, int size) {
        WorkspaceContext ctx = access.authorize(userId, workspaceId, Permission.VIEW_WORKSPACE);
        return PageResponse.from(
                memberships.findMembers(
                        ctx.tenantId(), PageResponse.request(page, size, Sort.by("joinedAt").and(Sort.by("id")))),
                view -> view);
    }

    @Transactional
    public MemberView update(
            UUID userId, UUID workspaceId, UUID memberId, WorkspaceRole newRole, Membership.Status newStatus) {
        WorkspaceContext ctx = access.authorize(userId, workspaceId, Permission.MANAGE_MEMBERS);

        // Lock first, then read: a concurrent change to the owner set is serialized behind this.
        List<Membership> activeOwners = memberships.lockActiveOwners(ctx.tenantId());
        Membership target = memberships
                .findByTenantIdAndId(ctx.tenantId(), memberId)
                .orElseThrow(() -> ApiException.notFound("MEMBER_NOT_FOUND", "The member was not found."));

        if (target.getUserId().equals(ctx.userId())) {
            throw ApiException.conflict("CANNOT_MODIFY_SELF", "You cannot change your own membership.");
        }
        WorkspaceRole role = newRole != null ? newRole : target.getRole();
        Membership.Status status = newStatus != null ? newStatus : target.getStatus();

        // Only an owner may change an owner or grant ownership.
        boolean touchesOwnership = target.getRole() == WorkspaceRole.OWNER || role == WorkspaceRole.OWNER;
        if (touchesOwnership && ctx.role() != WorkspaceRole.OWNER) {
            throw ApiException.forbidden("FORBIDDEN", "Only an owner can change workspace ownership.");
        }
        boolean removesActiveOwner =
                target.isActiveOwner() && (role != WorkspaceRole.OWNER || status != Membership.Status.ACTIVE);
        if (removesActiveOwner && activeOwners.size() <= 1) {
            throw ApiException.conflict("LAST_OWNER", "A workspace must keep at least one active owner.");
        }

        WorkspaceRole previousRole = target.getRole();
        Membership.Status previousStatus = target.getStatus();
        target.change(role, status, clock.instant());
        memberships.save(target);
        audit.record(
                ctx,
                "MEMBER_UPDATED",
                "membership",
                target.getId(),
                Map.of(
                        "previous_role", previousRole.name(),
                        "role", role.name(),
                        "previous_status", previousStatus.name(),
                        "status", status.name()));

        return memberships.findMember(ctx.tenantId(), target.getId()).orElseThrow();
    }
}
