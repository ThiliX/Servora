package com.servora.membership;

import com.servora.common.error.ApiException;
import com.servora.common.tenant.TenantContext;
import com.servora.workspace.Tenant;
import com.servora.workspace.TenantRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single entry point for tenant-scoped work. Every workspace operation calls {@link
 * #authorize} first.
 *
 * <p>The workspace ID from the URL is only a selector. This class proves the authenticated user is
 * an active member with the required permission, and only then sets the transaction-local tenant
 * context that Row-Level Security enforces.
 */
@Component
public class WorkspaceAccess {

    private final TenantContext tenantContext;
    private final MembershipRepository memberships;
    private final TenantRepository tenants;

    public WorkspaceAccess(TenantContext tenantContext, MembershipRepository memberships, TenantRepository tenants) {
        this.tenantContext = tenantContext;
        this.memberships = memberships;
        this.tenants = tenants;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public WorkspaceContext authorize(UUID userId, UUID workspaceId, Permission permission) {
        // With only the user bound, RLS exposes this user's own membership rows and nothing else.
        tenantContext.bindUser(userId);

        Membership membership = memberships
                .findByTenantIdAndUserId(workspaceId, userId)
                .filter(Membership::isActive)
                .orElseThrow(WorkspaceAccess::notFound);
        Tenant tenant = tenants.findById(workspaceId).orElseThrow(WorkspaceAccess::notFound);

        if (!tenant.isActive()) {
            throw ApiException.forbidden("WORKSPACE_SUSPENDED", "This workspace is suspended.");
        }
        if (!permission.grantedTo(membership.getRole())) {
            throw ApiException.forbidden("FORBIDDEN", "You do not have permission to perform this action.");
        }

        tenantContext.bindTenant(workspaceId);
        return new WorkspaceContext(workspaceId, userId, membership.getId(), membership.getRole());
    }

    /** Non-members get the same response as for a workspace that does not exist. */
    private static ApiException notFound() {
        return ApiException.notFound("WORKSPACE_NOT_FOUND", "The workspace was not found.");
    }
}
