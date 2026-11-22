package com.servora.workspace;

import com.servora.assistant.AssistantService;
import com.servora.audit.AuditService;
import com.servora.common.error.ApiException;
import com.servora.common.tenant.TenantContext;
import com.servora.knowledge.DocumentService;
import com.servora.membership.Membership;
import com.servora.membership.MembershipRepository;
import com.servora.membership.Permission;
import com.servora.membership.WorkspaceAccess;
import com.servora.membership.WorkspaceContext;
import com.servora.membership.WorkspaceRole;
import com.servora.widget.WidgetService;
import com.servora.usage.UsageService;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceService {

    public record WorkspaceView(
            UUID id,
            String name,
            String slug,
            String status,
            String timezone,
            String defaultLanguage,
            String industry,
            String logoUrl,
            WorkspaceRole role,
            Integer conversationRetentionDays,
            Instant createdAt) {

        static WorkspaceView of(Tenant tenant, WorkspaceRole role) {
            return new WorkspaceView(
                    tenant.getId(),
                    tenant.getName(),
                    tenant.getSlug(),
                    tenant.getStatus().name(),
                    tenant.getTimezone(),
                    tenant.getDefaultLanguage(),
                    tenant.getIndustry(),
                    tenant.getLogoUrl(),
                    role,
                    tenant.getConversationRetentionDays(),
                    tenant.getCreatedAt());
        }
    }

    public record Settings(String name, String timezone, String defaultLanguage, String industry, String logoUrl) {}

    private final TenantContext tenantContext;
    private final WorkspaceAccess access;
    private final TenantRepository tenants;
    private final MembershipRepository memberships;
    private final AuditService audit;
    private final AssistantService assistants;
    private final WidgetService widgets;
    private final DocumentService documents;
    private final UsageService usage;
    private final Clock clock;

    public WorkspaceService(
            TenantContext tenantContext,
            WorkspaceAccess access,
            TenantRepository tenants,
            MembershipRepository memberships,
            AuditService audit,
            AssistantService assistants,
            WidgetService widgets,
            DocumentService documents,
            UsageService usage,
            Clock clock) {
        this.tenantContext = tenantContext;
        this.access = access;
        this.tenants = tenants;
        this.memberships = memberships;
        this.audit = audit;
        this.assistants = assistants;
        this.widgets = widgets;
        this.documents = documents;
        this.usage = usage;
        this.clock = clock;
    }

    @Transactional
    public WorkspaceView create(UUID userId, String name, String slug, String timezone, String defaultLanguage) {
        Instant now = clock.instant();
        // The tenant ID is generated here, on the server, so it is safe to bind before the row exists.
        UUID tenantId = UUID.randomUUID();
        tenantContext.bindUser(userId);
        tenantContext.bindTenant(tenantId);

        Tenant tenant = new Tenant(
                tenantId,
                name.trim(),
                slug,
                validTimezone(timezone == null ? "UTC" : timezone),
                defaultLanguage == null ? "en" : defaultLanguage,
                now);
        try {
            tenants.saveAndFlush(tenant);
        } catch (DataIntegrityViolationException e) {
            // Other tenants' slugs are invisible under RLS, so uniqueness is detected by the constraint.
            throw ApiException.conflict("SLUG_TAKEN", "That workspace address is already in use.");
        }
        usage.createSubscription(tenantId);
        Membership owner = memberships.save(new Membership(tenantId, userId, WorkspaceRole.OWNER, now));
        WorkspaceContext ctx = new WorkspaceContext(tenantId, userId, owner.getId(), WorkspaceRole.OWNER);
        // Every workspace starts with a default assistant and widget.
        assistants.createDefault(ctx, tenant.getName(), tenant.getDefaultLanguage());
        widgets.createDefault(ctx, tenant.getName());
        documents.createDefaultSource(ctx);
        audit.record(ctx, "WORKSPACE_CREATED", "workspace", tenantId, Map.of("slug", slug));
        return WorkspaceView.of(tenant, WorkspaceRole.OWNER);
    }

    @Transactional(readOnly = true)
    public List<WorkspaceSummary> listMine(UUID userId) {
        tenantContext.bindUser(userId);
        return tenants.findWorkspacesOf(userId);
    }

    @Transactional(readOnly = true)
    public WorkspaceView get(UUID userId, UUID workspaceId) {
        WorkspaceContext ctx = access.authorize(userId, workspaceId, Permission.VIEW_WORKSPACE);
        return WorkspaceView.of(tenants.findById(ctx.tenantId()).orElseThrow(), ctx.role());
    }

    @Transactional
    public WorkspaceView update(UUID userId, UUID workspaceId, Settings changes) {
        WorkspaceContext ctx = access.authorize(userId, workspaceId, Permission.MANAGE_WORKSPACE_SETTINGS);
        Tenant tenant = tenants.findById(ctx.tenantId()).orElseThrow();
        tenant.updateSettings(
                changes.name() != null ? changes.name().trim() : tenant.getName(),
                changes.timezone() != null ? validTimezone(changes.timezone()) : tenant.getTimezone(),
                changes.defaultLanguage() != null ? changes.defaultLanguage() : tenant.getDefaultLanguage(),
                changes.industry() != null ? blankToNull(changes.industry()) : tenant.getIndustry(),
                changes.logoUrl() != null ? blankToNull(changes.logoUrl()) : tenant.getLogoUrl(),
                clock.instant());
        tenants.save(tenant);
        audit.record(ctx, "WORKSPACE_UPDATED", "workspace", tenant.getId(), Map.of());
        return WorkspaceView.of(tenant, ctx.role());
    }

    private static String validTimezone(String timezone) {
        try {
            return ZoneId.of(timezone).getId();
        } catch (DateTimeException e) {
            throw ApiException.badRequest("INVALID_TIMEZONE", "The timezone is not a valid IANA zone ID.");
        }
    }

    private static String blankToNull(String value) {
        return value.isBlank() ? null : value.trim();
    }
}
