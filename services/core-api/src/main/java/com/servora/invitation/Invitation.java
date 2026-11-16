package com.servora.invitation;

import com.servora.common.persistence.BaseEntity;
import com.servora.membership.WorkspaceRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "invitations")
public class Invitation extends BaseEntity {

    public enum Status {
        PENDING,
        ACCEPTED,
        REVOKED
    }

    @Column(nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private WorkspaceRole role;

    @Column(nullable = false, updatable = false)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false, updatable = false)
    private Instant expiresAt;

    private Instant acceptedAt;

    @Column(nullable = false, updatable = false)
    private UUID invitedByMembershipId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Invitation() {}

    public Invitation(
            UUID tenantId,
            String normalizedEmail,
            WorkspaceRole role,
            String tokenHash,
            Instant expiresAt,
            UUID invitedByMembershipId,
            Instant now) {
        super(UUID.randomUUID());
        this.tenantId = tenantId;
        this.email = normalizedEmail;
        this.role = role;
        this.tokenHash = tokenHash;
        this.status = Status.PENDING;
        this.expiresAt = expiresAt;
        this.invitedByMembershipId = invitedByMembershipId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void accept(Instant now) {
        this.status = Status.ACCEPTED;
        this.acceptedAt = now;
        this.updatedAt = now;
    }

    public void revoke(Instant now) {
        this.status = Status.REVOKED;
        this.updatedAt = now;
    }

    public boolean isPending() {
        return status == Status.PENDING;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getEmail() {
        return email;
    }

    public WorkspaceRole getRole() {
        return role;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
