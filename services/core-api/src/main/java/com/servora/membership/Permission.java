package com.servora.membership;

import static com.servora.membership.WorkspaceRole.ADMIN;
import static com.servora.membership.WorkspaceRole.AGENT;
import static com.servora.membership.WorkspaceRole.OWNER;
import static com.servora.membership.WorkspaceRole.SUPERVISOR;

import java.util.EnumSet;
import java.util.Set;

/**
 * Workspace capabilities and the roles that hold them. This is the single source of truth for the
 * role matrix; the frontend only mirrors it to hide controls and is never an authorization control.
 *
 * <p>Capabilities for later milestones are defined now so the whole matrix is reviewed and tested
 * in one place. An agent's access to conversations assigned to them is an object-level rule that
 * arrives with conversations and is not expressed here.
 */
public enum Permission {
    VIEW_WORKSPACE(OWNER, ADMIN, SUPERVISOR, AGENT),
    MANAGE_WORKSPACE_SETTINGS(OWNER, ADMIN),
    MANAGE_MEMBERS(OWNER, ADMIN),
    VIEW_AUDIT_LOG(OWNER, ADMIN),
    CONFIGURE_AI(OWNER, ADMIN),
    MANAGE_KNOWLEDGE(OWNER, ADMIN),
    VIEW_ALL_CONVERSATIONS(OWNER, ADMIN, SUPERVISOR),
    ASSIGN_TICKETS(OWNER, ADMIN, SUPERVISOR),
    REPLY_TO_CUSTOMERS(OWNER, ADMIN, SUPERVISOR, AGENT),
    MANAGE_SUBSCRIPTION(OWNER);

    private final Set<WorkspaceRole> roles;

    Permission(WorkspaceRole first, WorkspaceRole... rest) {
        this.roles = EnumSet.of(first, rest);
    }

    public boolean grantedTo(WorkspaceRole role) {
        return roles.contains(role);
    }
}
