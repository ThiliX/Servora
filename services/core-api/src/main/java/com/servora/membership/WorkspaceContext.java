package com.servora.membership;

import java.util.UUID;

/** Proof that a user's membership and permission were validated and the tenant context is set. */
public record WorkspaceContext(UUID tenantId, UUID userId, UUID membershipId, WorkspaceRole role) {}
