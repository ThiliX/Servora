package com.servora.workspace;

import com.servora.membership.WorkspaceRole;
import java.util.UUID;

/** One entry in the list of workspaces a user belongs to. Suspended workspaces are listed so members know why they cannot open them. */
public record WorkspaceSummary(UUID id, String name, String slug, WorkspaceRole role, Tenant.Status status, String suspendedReason) {}
