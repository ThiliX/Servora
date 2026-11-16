package com.servora.auth;

import com.servora.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User extends BaseEntity {

    public enum Status {
        PENDING_VERIFICATION,
        ACTIVE,
        DISABLED
    }

    @Column(nullable = false, updatable = false)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private Instant emailVerifiedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected User() {}

    public User(String normalizedEmail, String passwordHash, String displayName, Instant now) {
        super(UUID.randomUUID());
        this.email = normalizedEmail;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.status = Status.PENDING_VERIFICATION;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void markEmailVerified(Instant now) {
        if (status == Status.PENDING_VERIFICATION) {
            this.status = Status.ACTIVE;
            this.emailVerifiedAt = now;
            this.updatedAt = now;
        }
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }
}
