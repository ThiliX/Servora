package com.servora.widget;

import com.servora.audit.AuditService;
import com.servora.common.error.ApiException;
import com.servora.common.security.Tokens;
import com.servora.membership.Permission;
import com.servora.membership.WorkspaceAccess;
import com.servora.membership.WorkspaceContext;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Widget presentation settings, public key and allowed origins. */
@Service
public class WidgetService {

    public static final int MAX_ALLOWED_ORIGINS = 20;

    public enum Status {
        ACTIVE,
        DISABLED
    }

    public enum Position {
        BOTTOM_RIGHT,
        BOTTOM_LEFT
    }

    public record WidgetSettings(
            String name, String title, String themeColor, Position position, Boolean showBranding, Status status) {}

    public record WidgetView(
            UUID id,
            String name,
            String publicKey,
            Status status,
            String themeColor,
            Position position,
            String title,
            boolean showBranding,
            List<String> allowedOrigins,
            Instant updatedAt) {}

    private final JdbcClient jdbc;
    private final WorkspaceAccess access;
    private final AuditService audit;

    public WidgetService(JdbcClient jdbc, WorkspaceAccess access, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    /** Called when a workspace is created, inside that transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createDefault(WorkspaceContext ctx, String workspaceName) {
        jdbc.sql("""
                        INSERT INTO widgets (id, tenant_id, name, public_key, status, theme_color, position, title, show_branding)
                        VALUES (:id, :tenantId, 'Website widget', :publicKey, 'ACTIVE', '#4f46e5', 'BOTTOM_RIGHT', :title, true)
                        """)
                .param("id", UUID.randomUUID())
                .param("tenantId", ctx.tenantId())
                .param("publicKey", newPublicKey())
                .param("title", workspaceName)
                .update();
    }

    @Transactional(readOnly = true)
    public WidgetView get(UUID userId, UUID workspaceId) {
        return load(access.authorize(userId, workspaceId, Permission.CONFIGURE_AI));
    }

    @Transactional
    public WidgetView update(UUID userId, UUID workspaceId, WidgetSettings changes) {
        WorkspaceContext ctx = access.authorize(userId, workspaceId, Permission.CONFIGURE_AI);
        WidgetView current = load(ctx);
        jdbc.sql("""
                        UPDATE widgets
                        SET name = :name, title = :title, theme_color = :themeColor, position = :position,
                            show_branding = :showBranding, status = :status, updated_at = now()
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", ctx.tenantId())
                .param("id", current.id())
                .param("name", changes.name() != null ? changes.name().trim() : current.name())
                .param("title", changes.title() != null ? changes.title().trim() : current.title())
                .param("themeColor", changes.themeColor() != null ? changes.themeColor().toLowerCase() : current.themeColor())
                .param("position", (changes.position() != null ? changes.position() : current.position()).name())
                .param("showBranding", changes.showBranding() != null ? changes.showBranding() : current.showBranding())
                .param("status", (changes.status() != null ? changes.status() : current.status()).name())
                .update();
        audit.record(ctx, "WIDGET_UPDATED", "widget", current.id(), Map.of());
        return load(ctx);
    }

    /** Issues a new public key. The old key stops resolving immediately. */
    @Transactional
    public WidgetView rotateKey(UUID userId, UUID workspaceId) {
        WorkspaceContext ctx = access.authorize(userId, workspaceId, Permission.CONFIGURE_AI);
        UUID widgetId = jdbc.sql("""
                        UPDATE widgets SET public_key = :publicKey, updated_at = now()
                        WHERE tenant_id = :tenantId
                        RETURNING id
                        """)
                .param("tenantId", ctx.tenantId())
                .param("publicKey", newPublicKey())
                .query(UUID.class)
                .single();
        audit.record(ctx, "WIDGET_KEY_ROTATED", "widget", widgetId, Map.of());
        return load(ctx);
    }

    /** Replaces the whole allow-list. Each entry must be a plain origin; https is required except for localhost. */
    @Transactional
    public WidgetView replaceAllowedOrigins(UUID userId, UUID workspaceId, List<String> origins) {
        WorkspaceContext ctx = access.authorize(userId, workspaceId, Permission.CONFIGURE_AI);
        Set<String> canonical = new LinkedHashSet<>();
        for (String origin : origins) {
            String value = Origins.canonical(origin)
                    .orElseThrow(() -> ApiException.badRequest(
                            "INVALID_ORIGIN",
                            "Each origin must look like https://shop.example.com, with no path, query or wildcard."));
            if (!Origins.isSecureOrLocal(value)) {
                throw ApiException.badRequest("INVALID_ORIGIN", "Origins must use https, except for localhost.");
            }
            canonical.add(value);
        }
        if (canonical.size() > MAX_ALLOWED_ORIGINS) {
            throw ApiException.badRequest(
                    "TOO_MANY_ORIGINS", "A widget can have at most " + MAX_ALLOWED_ORIGINS + " allowed origins.");
        }

        UUID widgetId = load(ctx).id();
        jdbc.sql("DELETE FROM widget_allowed_origins WHERE tenant_id = :tenantId AND widget_id = :widgetId")
                .param("tenantId", ctx.tenantId())
                .param("widgetId", widgetId)
                .update();
        for (String origin : canonical) {
            jdbc.sql("""
                            INSERT INTO widget_allowed_origins (id, tenant_id, widget_id, origin)
                            VALUES (:id, :tenantId, :widgetId, :origin)
                            """)
                    .param("id", UUID.randomUUID())
                    .param("tenantId", ctx.tenantId())
                    .param("widgetId", widgetId)
                    .param("origin", origin)
                    .update();
        }
        audit.record(
                ctx, "WIDGET_ORIGINS_REPLACED", "widget", widgetId, Map.of("count", String.valueOf(canonical.size())));
        return load(ctx);
    }

    private WidgetView load(WorkspaceContext ctx) {
        List<String> origins = jdbc.sql("""
                        SELECT o.origin
                        FROM widget_allowed_origins o
                        WHERE o.tenant_id = :tenantId
                        ORDER BY o.origin
                        """)
                .param("tenantId", ctx.tenantId())
                .query(String.class)
                .list();
        return jdbc.sql("""
                        SELECT id, name, public_key, status, theme_color, position, title, show_branding, updated_at
                        FROM widgets
                        WHERE tenant_id = :tenantId
                        """)
                .param("tenantId", ctx.tenantId())
                .query((rs, rowNum) -> new WidgetView(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("public_key"),
                        Status.valueOf(rs.getString("status")),
                        rs.getString("theme_color"),
                        Position.valueOf(rs.getString("position")),
                        rs.getString("title"),
                        rs.getBoolean("show_branding"),
                        origins,
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .single();
    }

    private static String newPublicKey() {
        return "wk_" + Tokens.generate();
    }
}
