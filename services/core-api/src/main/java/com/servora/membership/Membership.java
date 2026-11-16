package com.servora.membership;

import com.servora.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "memberships")
public class Membership extends BaseEntity {

    public enum Status {
        ACTIVE,
        DISABLED
    }

    @Column(nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkspaceRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false, updatable = false)
    private Instant joinedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Membership() {}

    public Membership(UUID tenantId, UUID userId, WorkspaceRole role, Instant now) {
        super(UUID.randomUUID());
        this.tenantId = tenantId;
        this.userId = userId;
        this.role = role;
        this.status = Status.ACTIVE;
        this.joinedAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void change(WorkspaceRole newRole, Status newStatus, Instant now) {
        this.role = newRole;
        this.status = newStatus;
        this.updatedAt = now;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getUserId() {
        return userId;
    }

    public WorkspaceRole getRole() {
        return role;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public boolean isActiveOwner() {
        return isActive() && role == WorkspaceRole.OWNER;
    }
}
