package com.servora.workspace;

import com.servora.membership.WorkspaceRole;
import java.util.UUID;

/** One entry in the list of workspaces a user belongs to. */
public record WorkspaceSummary(UUID id, String name, String slug, WorkspaceRole role) {}
