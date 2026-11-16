package com.servora.workspace;

import com.servora.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A tenant. The API and UI call it a workspace. */
@Entity
@Table(name = "tenants")
public class Tenant extends BaseEntity {

    public enum Status {
        ACTIVE,
        SUSPENDED
    }

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, updatable = false)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private String timezone;

    @Column(nullable = false)
    private String defaultLanguage;

    private String industry;

    private String logoUrl;

    @Column(nullable = false)
    private String planCode;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Tenant() {}

    public Tenant(UUID id, String name, String slug, String timezone, String defaultLanguage, Instant now) {
        super(id);
        this.name = name;
        this.slug = slug;
        this.status = Status.ACTIVE;
        this.timezone = timezone;
        this.defaultLanguage = defaultLanguage;
        this.planCode = "FREE";
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void updateSettings(
            String name, String timezone, String defaultLanguage, String industry, String logoUrl, Instant now) {
        this.name = name;
        this.timezone = timezone;
        this.defaultLanguage = defaultLanguage;
        this.industry = industry;
        this.logoUrl = logoUrl;
        this.updatedAt = now;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public Status getStatus() {
        return status;
    }

    public String getTimezone() {
        return timezone;
    }

    public String getDefaultLanguage() {
        return defaultLanguage;
    }

    public String getIndustry() {
        return industry;
    }

    public String getLogoUrl() {
        return logoUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
