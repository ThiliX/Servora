package com.servora.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.servora.support.IntegrationTestBase;
import com.servora.support.TwoTenants;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M7: the cross-tenant object-ID substitution suite, for every tenant-scoped endpoint in one place
 * (a release requirement). Tenant A owns one of every resource. Tenant B's owner then calls
 * every endpoint twice: through A's workspace path, and with A's object IDs inside B's own
 * workspace path. Every call must be refused as not found, and none may change A's data.
 *
 * <p>When an endpoint is added, add it to {@link #workspacePaths} or {@link #objectPaths}.
 */
class CrossTenantMatrixIntegrationTest extends IntegrationTestBase {

    private static final String SITE = "https://shop.example.test";
    private static final String ASSISTANT = """
            {"name": "Intruder", "welcome_message": "Hi", "fallback_message": "Sorry", "language": "en",
             "tone": "FRIENDLY", "automation_enabled": true, "confidence_policy": "BALANCED"}
            """;

    /** One call: method, path relative to a workspace, JSON body or null, and whether it is an upload. */
    private record Call(String method, String path, String body, boolean upload) {

        static Call of(String method, String path, String body) {
            return new Call(method, path, body, false);
        }

        static Call upload(String path) {
            return new Call("POST", path, null, true);
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    @Autowired
    private ScriptedAi ai;

    private TwoTenants tenants;
    private UUID conversation;
    private String ticket;
    private UUID document;
    private UUID note;
    private UUID invitation;
    private UUID notification;

    @BeforeEach
    void giveTenantAOneOfEverything() throws SQLException {
        tenants = TwoTenants.create(this);
        UUID a = tenants.techStore();
        assertThat(send("PUT", base(a) + "/widget/allowed-origins", "{\"origins\": [\"" + SITE + "\"]}",
                        tenants.ownerA().accessToken())
                .status())
                .isEqualTo(200);
        String key = get(base(a) + "/widget", tenants.ownerA()).json("$.public_key");
        ai.script = null;

        Response session = send("POST", "/api/v1/public/widgets/" + key + "/sessions", null, null, "Origin", SITE);
        String token = session.json("$.token");
        conversation = conversationOf(token);
        send("POST", "/api/v1/public/conversation/handoff", null, token);
        ticket = scalar("SELECT id::text FROM tickets WHERE conversation_id = ?", conversation);
        assertThat(send("PUT", base(a) + "/tickets/" + ticket + "/assignee",
                        "{\"membership_id\": \"" + tenants.agentA().memberId() + "\"}", tenants.ownerA().accessToken())
                .status())
                .isEqualTo(200);
        notification = UUID.fromString(scalar(
                "SELECT id::text FROM notification_events WHERE ticket_id = ?::uuid", ticket));
        note = UUID.fromString(post(base(a) + "/conversations/" + conversation + "/notes", "{\"body\": \"Private\"}",
                        tenants.ownerA())
                .json("$.id"));
        invitation = UUID.fromString(post(base(a) + "/invitations",
                        "{\"email\": \"" + uniqueEmail("invitee") + "\", \"role\": \"AGENT\"}", tenants.ownerA())
                .json("$.id"));
        Response upload = uploadFile(base(a) + "/knowledge/documents", "faq.md",
                ("faq " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8), tenants.ownerA());
        assertThat(upload.status()).as(upload.body()).isEqualTo(202);
        document = UUID.fromString(upload.json("$.id"));
    }

    @Test
    void everyEndpointOfAnotherWorkspaceIsNotFound() throws SQLException {
        String before = fingerprintOfA();
        List<String> failures = new ArrayList<>();
        List<Call> calls = new ArrayList<>(workspacePaths());
        calls.addAll(objectPaths());
        for (Call call : calls) {
            Response response = call(tenants.techStore(), call);
            if (response.status() != 404 || !"WORKSPACE_NOT_FOUND".equals(response.errorCode())) {
                failures.add(call + " -> " + response.status() + " " + response.body());
            }
        }
        assertThat(failures).as("calls through tenant A's path by tenant B's owner").isEmpty();
        assertThat(fingerprintOfA()).isEqualTo(before);
    }

    @Test
    void objectIdsOfAnotherWorkspaceAreNotFoundInsideTheCallersOwnWorkspace() throws SQLException {
        String before = fingerprintOfA();
        List<String> failures = new ArrayList<>();
        for (Call call : objectPaths()) {
            Response response = call(tenants.hotelPro(), call);
            // RESOURCE_NOT_FOUND means no such route, which would make the check meaningless.
            if (response.status() != 404 || "RESOURCE_NOT_FOUND".equals(response.errorCode())) {
                failures.add(call + " -> " + response.status() + " " + response.body());
            }
        }
        assertThat(failures).as("tenant A's object IDs inside tenant B's path").isEmpty();
        assertThat(fingerprintOfA()).isEqualTo(before);
    }

    @Test
    void markingAnotherWorkspacesNotificationsAsReadChangesNothing() throws SQLException {
        Response response = post(base(tenants.hotelPro()) + "/notifications/read",
                "{\"ids\": [\"" + notification + "\"]}", tenants.ownerB());
        assertThat(response.status()).isEqualTo(200);
        assertThat(scalar("SELECT (read_at IS NULL)::text FROM notification_events WHERE id = ?", notification))
                .isEqualTo("true");
    }

    @Test
    void listsInTheCallersOwnWorkspaceNeverContainAnotherWorkspacesRows() {
        UUID b = tenants.hotelPro();
        for (String path : List.of("/conversations", "/tickets", "/tickets?state=DONE", "/knowledge/documents",
                "/members", "/invitations", "/audit-events", "/notifications", "/tickets/assignees")) {
            Response response = get(base(b) + path, tenants.ownerB());
            assertThat(response.status()).as(path).isEqualTo(200);
            assertThat(response.body())
                    .as(path)
                    .doesNotContain(conversation.toString(), ticket, document.toString(), note.toString(),
                            invitation.toString(), notification.toString(), tenants.techStore().toString());
        }
        Response export = get(base(b) + "/export", tenants.ownerB());
        assertThat(export.body()).doesNotContain(conversation.toString(), "TechStore", "Private");
    }

    // --- the endpoint inventory ---------------------------------------------------------------

    /** Endpoints that name only the workspace. */
    private List<Call> workspacePaths() {
        return List.of(
                Call.of("GET", "", null),
                Call.of("PATCH", "", "{\"name\": \"Taken over\"}"),
                Call.of("GET", "/analytics", null),
                Call.of("GET", "/usage", null),
                Call.of("GET", "/export", null),
                Call.of("PUT", "/retention", "{\"conversation_retention_days\": 30}"),
                Call.of("GET", "/assistant", null),
                Call.of("PUT", "/assistant", ASSISTANT),
                Call.of("POST", "/assistant/test", "{\"question\": \"What is the refund policy?\"}"),
                Call.of("GET", "/assistant/versions", null),
                Call.of("GET", "/audit-events", null),
                Call.of("GET", "/conversations", null),
                Call.of("GET", "/invitations", null),
                Call.of("POST", "/invitations", "{\"email\": \"intruder@example.test\", \"role\": \"ADMIN\"}"),
                Call.of("GET", "/knowledge/documents", null),
                Call.upload("/knowledge/documents"),
                Call.of("GET", "/members", null),
                Call.of("GET", "/notifications", null),
                Call.of("POST", "/notifications/read", "{\"ids\": []}"),
                Call.of("GET", "/tickets", null),
                Call.of("GET", "/tickets/assignees", null),
                Call.of("GET", "/widget", null),
                Call.of("PATCH", "/widget", "{\"title\": \"Taken over\"}"),
                Call.of("PUT", "/widget/allowed-origins", "{\"origins\": [\"https://evil.example.test\"]}"),
                Call.of("POST", "/widget/rotate-key", null));
    }

    /** Endpoints that also name an object of tenant A. */
    private List<Call> objectPaths() {
        String c = "/conversations/" + conversation;
        String t = "/tickets/" + ticket;
        String d = "/knowledge/documents/" + document;
        return List.of(
                Call.of("GET", c, null),
                Call.of("GET", c + "/messages", null),
                Call.of("GET", c + "/notes", null),
                Call.of("POST", c + "/notes", "{\"body\": \"Intruder\"}"),
                Call.of("POST", c + "/replies", "{\"body\": \"Intruder\"}"),
                Call.of("POST", c + "/suggestions", null),
                Call.of("POST", c + "/takeover", "{\"expected_version\": 1}"),
                Call.of("DELETE", c, null),
                Call.of("GET", t, null),
                Call.of("PATCH", t, "{\"priority\": \"LOW\"}"),
                Call.of("PUT", t + "/assignee", "{\"membership_id\": null}"),
                Call.of("POST", t + "/resolve", "{\"outcome\": \"SOLVED\"}"),
                Call.of("POST", t + "/close", null),
                Call.of("GET", d, null),
                Call.of("POST", d + "/archive", null),
                Call.of("POST", d + "/reprocess", null),
                Call.upload(d + "/replace"),
                Call.of("DELETE", d, null),
                Call.of("PATCH", "/members/" + tenants.agentA().memberId(), "{\"role\": \"ADMIN\"}"),
                Call.of("DELETE", "/invitations/" + invitation, null));
    }

    // --- helpers ------------------------------------------------------------------------------

    private Response call(UUID workspace, Call call) {
        String path = base(workspace) + call.path();
        if (call.upload()) {
            return uploadFile(path, "intruder.md", ("intruder " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8),
                    tenants.ownerB());
        }
        return send(call.method(), path, call.body(), tenants.ownerB().accessToken());
    }

    /** Everything about tenant A that any of the calls could change. */
    private String fingerprintOfA() throws SQLException {
        UUID a = tenants.techStore();
        return String.join("|",
                scalar("SELECT name || status || coalesce(conversation_retention_days::text, '-') FROM tenants WHERE id = ?", a),
                scalar("SELECT name || current_version FROM ai_assistants WHERE tenant_id = ?", a),
                scalar("SELECT public_key || title FROM widgets WHERE tenant_id = ?", a),
                scalar("SELECT string_agg(origin, ',' ORDER BY origin) FROM widget_allowed_origins WHERE tenant_id = ?", a),
                scalar("SELECT string_agg(id::text || role || status, ',' ORDER BY id) FROM memberships WHERE tenant_id = ?", a),
                scalar("SELECT string_agg(id::text || status, ',' ORDER BY id) FROM invitations WHERE tenant_id = ?", a),
                scalar("SELECT string_agg(id::text || status, ',' ORDER BY id) FROM documents WHERE tenant_id = ?", a),
                scalar("SELECT string_agg(id::text || status || control_mode || version, ',' ORDER BY id) FROM conversations WHERE tenant_id = ?", a),
                scalar("SELECT string_agg(id::text || status || priority || coalesce(assignee_membership_id::text, '-'), ',' ORDER BY id) FROM tickets WHERE tenant_id = ?", a),
                scalar("SELECT count(*)::text FROM internal_notes WHERE tenant_id = ?", a),
                scalar("SELECT count(*)::text FROM messages WHERE tenant_id = ?", a),
                scalar("SELECT count(*)::text FROM audit_events WHERE tenant_id = ?", a));
    }

    private static String scalar(String sql, Object parameter) throws SQLException {
        try (Connection connection = ownerConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter);
            ResultSet row = statement.executeQuery();
            return row.next() ? String.valueOf(row.getString(1)) : "none";
        }
    }

    private static String base(UUID workspaceId) {
        return "/api/v1/workspaces/" + workspaceId;
    }

    private static UUID conversationOf(String token) {
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        return UUID.fromString(JsonPath.read(payload, "$.conversation_id"));
    }
}
