package com.servora.conversation;

import com.servora.common.error.ApiException;
import com.servora.common.ratelimit.RateLimiter;
import com.servora.common.tenant.TenantContext;
import com.servora.conversation.WidgetTokens.IssuedToken;
import com.servora.conversation.WidgetTokens.WidgetSession;
import com.servora.usage.UsageService;
import com.servora.widget.Origins;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Resolves a public widget key and issues widget sessions.
 *
 * <p>The key is looked up in a transaction that may read only the widget with that key; the
 * widget's tenant is bound from that row and nothing else. The request's Origin header must match
 * one of the widget's allowed origins exactly. Unknown, rotated and disabled keys all give the same
 * answer, so a caller cannot tell them apart.
 */
@Service
public class PublicWidgetService {

    private static final Pattern KEY_FORMAT = Pattern.compile("^wk_[A-Za-z0-9_-]{10,100}$");

    public record WidgetConfig(
            String title, String themeColor, String position, boolean showBranding, String welcomeMessage) {}

    public record SessionView(String token, Instant expiresAt, UUID conversationId, WidgetConfig config) {}

    private record Resolved(UUID tenantId, UUID widgetId, WidgetConfig config, List<String> origins) {}

    private final JdbcClient jdbc;
    private final TenantContext tenantContext;
    private final WidgetTokens tokens;
    private final RateLimiter rateLimiter;
    private final WidgetProperties properties;
    private final UsageService usage;
    private final TransactionTemplate transactions;

    public PublicWidgetService(
            JdbcClient jdbc,
            TenantContext tenantContext,
            WidgetTokens tokens,
            RateLimiter rateLimiter,
            WidgetProperties properties,
            UsageService usage,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.tokens = tokens;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.usage = usage;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public WidgetConfig config(String publicKey, String origin, String clientAddress) {
        limit("config-ip:" + RateLimiter.hashed(clientAddress), properties.configPerIpPerMinute(), Duration.ofMinutes(1));
        Resolved widget = transactions.execute(status -> resolve(publicKey)).orElseThrow(PublicWidgetService::notFound);
        requireOrigin(widget, origin);
        return widget.config();
    }

    /** Used by the CORS filter to decide whether a browser may call the widget endpoints from {@code origin}. */
    public boolean isOriginAllowed(String publicKey, String origin) {
        Optional<Resolved> widget = transactions.execute(status -> resolve(publicKey));
        return widget != null && widget.isPresent() && Origins.isAllowed(widget.get().origins(), origin);
    }

    /** Starts an anonymous customer's conversation and returns a token bound to it. */
    public SessionView createSession(String publicKey, String origin, String clientAddress) {
        limit("session-ip:" + RateLimiter.hashed(clientAddress), properties.sessionsPerIpPerHour(), Duration.ofHours(1));
        return transactions.execute(status -> {
            Resolved widget = resolve(publicKey).orElseThrow(PublicWidgetService::notFound);
            requireOrigin(widget, origin);
            if (!usage.conversationAllowed(widget.tenantId())) {
                // The customer is not told about the workspace's plan; the team sees it on its usage page.
                throw UsageService.limitReached("Chat is not available right now. Please try again later.");
            }
            UUID customerId = UUID.randomUUID();
            UUID conversationId = UUID.randomUUID();
            jdbc.sql("INSERT INTO customers (id, tenant_id, widget_id) VALUES (:id, :tenantId, :widgetId)")
                    .param("id", customerId)
                    .param("tenantId", widget.tenantId())
                    .param("widgetId", widget.widgetId())
                    .update();
            jdbc.sql("""
                            INSERT INTO conversations (id, tenant_id, customer_id, widget_id, origin)
                            VALUES (:id, :tenantId, :customerId, :widgetId, :origin)
                            """)
                    .param("id", conversationId)
                    .param("tenantId", widget.tenantId())
                    .param("customerId", customerId)
                    .param("widgetId", widget.widgetId())
                    .param("origin", Origins.canonical(origin).orElseThrow())
                    .update();
            usage.recordConversationStarted(widget.tenantId(), conversationId);
            IssuedToken token = tokens.issue(
                    new WidgetSession(widget.tenantId(), widget.widgetId(), customerId, conversationId));
            return new SessionView(token.token(), token.expiresAt(), conversationId, widget.config());
        });
    }

    /** Runs inside a transaction. Binds the widget's tenant when the key resolves to an active widget. */
    private Optional<Resolved> resolve(String publicKey) {
        if (publicKey == null || !KEY_FORMAT.matcher(publicKey).matches()) {
            return Optional.empty();
        }
        tenantContext.bindWidgetKey(publicKey);
        Optional<Map<String, Object>> row = jdbc.sql("""
                        SELECT id, tenant_id, status, title, theme_color, position, show_branding
                        FROM widgets WHERE public_key = :key
                        """)
                .param("key", publicKey)
                .query()
                .listOfRows()
                .stream()
                .findFirst();
        if (row.isEmpty() || !"ACTIVE".equals(row.get().get("status"))) {
            return Optional.empty();
        }
        UUID tenantId = (UUID) row.get().get("tenant_id");
        UUID widgetId = (UUID) row.get().get("id");
        tenantContext.bindTenant(tenantId);
        boolean tenantActive = jdbc.sql("SELECT count(*) FROM tenants WHERE id = :tenantId AND status = 'ACTIVE'")
                        .param("tenantId", tenantId)
                        .query(Long.class)
                        .single()
                == 1;
        if (!tenantActive) {
            return Optional.empty();
        }
        List<String> origins = jdbc.sql(
                        "SELECT origin FROM widget_allowed_origins WHERE tenant_id = :tenantId AND widget_id = :widgetId")
                .param("tenantId", tenantId)
                .param("widgetId", widgetId)
                .query(String.class)
                .list();
        String welcome = jdbc.sql("SELECT welcome_message FROM ai_assistants WHERE tenant_id = :tenantId")
                .param("tenantId", tenantId)
                .query(String.class)
                .optional()
                .orElse("");
        WidgetConfig config = new WidgetConfig(
                (String) row.get().get("title"),
                (String) row.get().get("theme_color"),
                (String) row.get().get("position"),
                (Boolean) row.get().get("show_branding"),
                welcome);
        return Optional.of(new Resolved(tenantId, widgetId, config, origins));
    }

    private static void requireOrigin(Resolved widget, String origin) {
        if (!Origins.isAllowed(widget.origins(), origin)) {
            throw ApiException.forbidden("ORIGIN_NOT_ALLOWED", "This website is not allowed to use this widget.");
        }
    }

    private void limit(String bucket, int limit, Duration window) {
        if (!rateLimiter.tryAcquire(bucket, limit, window)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "WIDGET_RATE_LIMITED", "Too many requests. Try again shortly.");
        }
    }

    private static ApiException notFound() {
        return ApiException.notFound("WIDGET_NOT_FOUND", "This widget is not available.");
    }
}
