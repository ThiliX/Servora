package com.servora.membership;

import java.time.Instant;
import java.util.UUID;

/** A membership joined with the member's public profile. */
public record MemberView(
        UUID id,
        UUID userId,
        String email,
        String displayName,
        WorkspaceRole role,
        Membership.Status status,
        Instant joinedAt) {}
