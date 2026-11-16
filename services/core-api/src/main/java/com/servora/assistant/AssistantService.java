package com.servora.assistant;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.servora.audit.AuditService;
import com.servora.common.web.PageResponse;
import com.servora.membership.Permission;
import com.servora.membership.WorkspaceAccess;
import com.servora.membership.WorkspaceContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The workspace's AI assistant configuration. Every change bumps the version and stores an
 * immutable snapshot, so any answer can later be traced to the exact configuration that produced it.
 */
@Service
public class AssistantService {

    public enum Tone {
        FRIENDLY,
        PROFESSIONAL,
        CONCISE
    }

    /** How readily the assistant answers. STRICT prefers handing off when evidence is thin. */
    public enum ConfidencePolicy {
        STRICT,
        BALANCED
    }

    public record AssistantConfig(
            String name,
            String welcomeMessage,
            String fallbackMessage,
            String language,
            Tone tone,
            boolean automationEnabled,
            ConfidencePolicy confidencePolicy) {}

    public record AssistantView(
            UUID id,
            String name,
            String welcomeMessage,
            String fallbackMessage,
            String language,
            Tone tone,
            boolean automationEnabled,
            ConfidencePolicy confidencePolicy,
            int currentVersion,
            Instant updatedAt) {}

    public record VersionView(int version, @JsonRawValue String config, UUID createdByUserId, Instant createdAt) {}

    private static final String SELECT_ASSISTANT = """
            SELECT id, name, welcome_message, fallback_message, language, tone, automation_enabled,
                   confidence_policy, current_version, updated_at
            FROM ai_assistants
            WHERE tenant_id = :tenantId
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final WorkspaceAccess access;
    private final AuditService audit;

    public AssistantService(JdbcClient jdbc, ObjectMapper json, WorkspaceAccess access, AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.access = access;
        this.audit = audit;
    }

    /** Called when a workspace is created, inside that transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createDefault(WorkspaceContext ctx, String workspaceName, String language) {
        AssistantConfig config = new AssistantConfig(
                workspaceName + " Assistant",
                "Hi! How can I help you today?",
                "I am not sure about that. Would you like to talk to a member of our team?",
                language,
                Tone.FRIENDLY,
                true,
                ConfidencePolicy.STRICT);
        UUID assistantId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO ai_assistants (id, tenant_id, name, welcome_message, fallback_message, language,
                                                   tone, automation_enabled, confidence_policy, current_version)
                        VALUES (:id, :tenantId, :name, :welcome, :fallback, :language, :tone, :automation, :confidence, 1)
                        """)
                .param("id", assistantId)
                .param("tenantId", ctx.tenantId())
                .params(configParams(config))
                .update();
        insertVersion(ctx, assistantId, 1, config);
    }

    @Transactional(readOnly = true)
    public AssistantView get(UUID userId, UUID workspaceId) {
        WorkspaceContext ctx = access.authorize(userId, workspaceId, Permission.CONFIGURE_AI);
        return load(ctx);
    }

    @Transactional
    public AssistantView update(UUID userId, UUID workspaceId, AssistantConfig config) {
        WorkspaceContext ctx = access.authorize(userId, workspaceId, Permission.CONFIGURE_AI);
        // The UPDATE locks the row, so concurrent edits take consecutive version numbers.
        Map<String, Object> updated = jdbc.sql("""
                        UPDATE ai_assistants
                        SET name = :name, welcome_message = :welcome, fallback_message = :fallback,
                            language = :language, tone = :tone, automation_enabled = :automation,
                            confidence_policy = :confidence, current_version = current_version + 1,
                            updated_at = now()
                        WHERE tenant_id = :tenantId
                        RETURNING id, current_version
                        """)
                .param("tenantId", ctx.tenantId())
                .params(configParams(config))
                .query()
                .singleRow();
        UUID assistantId = (UUID) updated.get("id");
        int version = (Integer) updated.get("current_version");
        insertVersion(ctx, assistantId, version, config);
        audit.record(ctx, "ASSISTANT_UPDATED", "assistant", assistantId, Map.of("version", String.valueOf(version)));
        return load(ctx);
    }

    @Transactional(readOnly = true)
    public PageResponse<VersionView> versions(UUID userId, UUID workspaceId, int page, int size) {
        WorkspaceContext ctx = access.authorize(userId, workspaceId, Permission.CONFIGURE_AI);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), PageResponse.MAX_SIZE);
        List<VersionView> items = jdbc.sql("""
                        SELECT version, config::text AS config, created_by_user_id, created_at
                        FROM assistant_versions
                        WHERE tenant_id = :tenantId
                        ORDER BY version DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .param("tenantId", ctx.tenantId())
                .param("limit", safeSize)
                .param("offset", (long) safePage * safeSize)
                .query((rs, rowNum) -> new VersionView(
                        rs.getInt("version"),
                        rs.getString("config"),
                        rs.getObject("created_by_user_id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
        long total = jdbc.sql("SELECT count(*) FROM assistant_versions WHERE tenant_id = :tenantId")
                .param("tenantId", ctx.tenantId())
                .query(Long.class)
                .single();
        return new PageResponse<>(items, safePage, safeSize, total);
    }

    private AssistantView load(WorkspaceContext ctx) {
        return jdbc.sql(SELECT_ASSISTANT)
                .param("tenantId", ctx.tenantId())
                .query(AssistantService::mapAssistant)
                .single();
    }

    private void insertVersion(WorkspaceContext ctx, UUID assistantId, int version, AssistantConfig config) {
        jdbc.sql("""
                        INSERT INTO assistant_versions (id, tenant_id, assistant_id, version, config, created_by_user_id)
                        VALUES (:id, :tenantId, :assistantId, :version, CAST(:config AS jsonb), :userId)
                        """)
                .param("id", UUID.randomUUID())
                .param("tenantId", ctx.tenantId())
                .param("assistantId", assistantId)
                .param("version", version)
                .param("config", json.writeValueAsString(config))
                .param("userId", ctx.userId())
                .update();
    }

    private static Map<String, Object> configParams(AssistantConfig config) {
        return Map.of(
                "name", config.name().trim(),
                "welcome", config.welcomeMessage().trim(),
                "fallback", config.fallbackMessage().trim(),
                "language", config.language(),
                "tone", config.tone().name(),
                "automation", config.automationEnabled(),
                "confidence", config.confidencePolicy().name());
    }

    private static AssistantView mapAssistant(ResultSet rs, int rowNum) throws SQLException {
        return new AssistantView(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("welcome_message"),
                rs.getString("fallback_message"),
                rs.getString("language"),
                Tone.valueOf(rs.getString("tone")),
                rs.getBoolean("automation_enabled"),
                ConfidencePolicy.valueOf(rs.getString("confidence_policy")),
                rs.getInt("current_version"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
