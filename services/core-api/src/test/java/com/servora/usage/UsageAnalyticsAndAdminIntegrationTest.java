package com.servora.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import com.servora.ai.AiAnswer;
import com.servora.ai.AiAnswer.Citation;
import com.servora.ai.AiAnswer.RetrievalHit;
import com.servora.privacy.RetentionJob;
import com.servora.support.IntegrationTestBase;
import com.servora.support.TwoTenants;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M6: usage metering and plan limits (FR-038/043), analytics (FR-038), audit coverage (FR-041),
 * retention, deletion and export (FR-042), and platform administration (FR-043), each with
 * cross-tenant negative checks.
 */
class UsageAnalyticsAndAdminIntegrationTest extends IntegrationTestBase {

    private static final String SITE = "https://shop.example.test";

    @Autowired
    private ScriptedAi ai;

    @Autowired
    private RetentionJob retention;

    private TwoTenants tenants;
    private String widgetKeyA;
    private UUID chunkA;
    private UUID documentA;

    @BeforeEach
    void setUp() throws SQLException {
        clock.reset();
        tenants = TwoTenants.create(this);
        widgetKeyA = publishWidget(tenants.techStore(), tenants.ownerA());
        publishWidget(tenants.hotelPro(), tenants.ownerB());
        documentA = UUID.randomUUID();
        chunkA = seedChunk(tenants.techStore(), documentA);
        ai.script = (tenantId, assistant, question) -> answered();
        ai.questions.clear();
    }

    // --- metering -----------------------------------------------------------------------------

    @Test
    void anAiReplyIsMeteredWithTokensAndEstimatedCost() {
        String token = session(widgetKeyA);
        assertThat(customerSays(token, "Refunds?").status()).isEqualTo(200);

        Response usage = get(base(tenants.techStore()) + "/usage", tenants.ownerA());
        assertThat(usage.status()).as(usage.body()).isEqualTo(200);
        assertThat(usage.<Integer>json("$.used.ai_requests")).isEqualTo(1);
        assertThat(usage.<Integer>json("$.used.input_tokens")).isEqualTo(10);
        assertThat(usage.<Integer>json("$.used.output_tokens")).isEqualTo(5);
        assertThat(usage.<Integer>json("$.used.conversations")).isEqualTo(1);
        // 10 x $3 + 5 x $15 per million tokens = $0.000105, shown to four places.
        assertThat(usage.<Double>json("$.used.estimated_cost_usd")).isEqualTo(0.0001);
        assertThat(usage.<Boolean>json("$.cost_configured")).isTrue();
        assertThat(usage.<String>json("$.plan.code")).isEqualTo("FREE");
        assertThat(usage.<String>json("$.by_source[0].source")).isEqualTo("WIDGET");
        assertThat(usage.<List<String>>json("$.plans[*].code")).contains("FREE", "STARTER", "GROWTH");

        // The other workspace's usage is untouched, and it cannot read this one's.
        Response other = get(base(tenants.hotelPro()) + "/usage", tenants.ownerB());
        assertThat(other.<Integer>json("$.used.ai_requests")).isZero();
        assertThat(get(base(tenants.techStore()) + "/usage", tenants.ownerB()).status()).isEqualTo(404);
        // Agents do not see usage and plan.
        assertThat(get(base(tenants.techStore()) + "/usage", tenants.agentA().session()).status()).isEqualTo(403);
    }

    @Test
    void documentUploadsAreMetered() {
        Response upload = uploadFile(base(tenants.techStore()) + "/knowledge/documents", "faq.md",
                ("faq " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8), tenants.ownerA());
        assertThat(upload.status()).as(upload.body()).isEqualTo(202);
        Response usage = get(base(tenants.techStore()) + "/usage", tenants.ownerA());
        // The seeded document plus the uploaded one.
        assertThat(usage.<Integer>json("$.used.documents")).isEqualTo(2);
    }

    // --- plan limits --------------------------------------------------------------------------

    @Test
    void overTheAiLimitTheWidgetOffersAPersonWithoutAskingTheModel() throws SQLException {
        assignPlan(tenants.techStore(), limitedPlan(1, 1_000_000, 100, 100));
        String token = session(widgetKeyA);
        assertThat(customerSays(token, "First question").status()).isEqualTo(200);
        assertThat(ai.questions).hasSize(1);

        Response second = customerSays(token, "Second question");
        assertThat(second.status()).isEqualTo(200);
        assertThat(ai.questions).as("the model is not asked over the limit").hasSize(1);
        UUID conversation = conversationOf(token);
        assertThat(count("""
                SELECT count(*) FROM ai_interactions i JOIN messages m ON m.interaction_id = i.id
                WHERE m.conversation_id = ? AND i.outcome = 'OVER_LIMIT'
                """, conversation)).isEqualTo(1);
        // The customer is handed to a person through the normal escalation path.
        assertThat(count("SELECT count(*) FROM tickets WHERE conversation_id = ? AND reason = 'SERVICE_ERROR'", conversation))
                .isEqualTo(1);
        // A refused request is not metered.
        assertThat(get(base(tenants.techStore()) + "/usage", tenants.ownerA()).<Integer>json("$.used.ai_requests"))
                .isEqualTo(1);

        // The playground and agent suggestions get a clear error code instead.
        Response playground = post(base(tenants.techStore()) + "/assistant/test",
                "{\"question\": \"What is the refund policy?\"}", tenants.ownerA());
        assertThat(playground.status()).isEqualTo(403);
        assertThat(playground.errorCode()).isEqualTo("PLAN_LIMIT_REACHED");
        Response suggestion = post(base(tenants.techStore()) + "/conversations/" + conversation + "/suggestions", null,
                tenants.ownerA());
        assertThat(suggestion.status()).isEqualTo(403);
        assertThat(suggestion.errorCode()).isEqualTo("PLAN_LIMIT_REACHED");

        // The other workspace, on its own plan, is unaffected.
        assertThat(post(base(tenants.hotelPro()) + "/assistant/test", "{\"question\": \"Breakfast?\"}", tenants.ownerB())
                        .status())
                .isEqualTo(200);
    }

    @Test
    void theTokenLimitAlsoStopsAiRequests() throws SQLException {
        // One reply uses 15 tokens; the plan allows 15.
        assignPlan(tenants.techStore(), limitedPlan(100, 15, 100, 100));
        String token = session(widgetKeyA);
        customerSays(token, "First");
        customerSays(token, "Second");
        assertThat(ai.questions).hasSize(1);
    }

    @Test
    void limitsResetAtTheStartOfTheNextMonth() throws SQLException {
        assignPlan(tenants.techStore(), limitedPlan(1, 1_000_000, 100, 100));
        customerSays(session(widgetKeyA), "First");
        Response refused = post(base(tenants.techStore()) + "/assistant/test", "{\"question\": \"Q?\"}", tenants.ownerA());
        assertThat(refused.errorCode()).isEqualTo("PLAN_LIMIT_REACHED");

        clock.advance(Duration.ofDays(32));
        Session owner = signIn(tenants.ownerA().email());
        Response allowed = post(base(tenants.techStore()) + "/assistant/test", "{\"question\": \"Q?\"}", owner);
        assertThat(allowed.status()).as(allowed.body()).isEqualTo(200);
    }

    @Test
    void overTheConversationLimitNewChatsAreRefusedPolitely() throws SQLException {
        assignPlan(tenants.techStore(), limitedPlan(100, 1_000_000, 1, 100));
        session(widgetKeyA);
        Response refused = send("POST", "/api/v1/public/widgets/" + widgetKeyA + "/sessions", null, null, "Origin", SITE);
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.errorCode()).isEqualTo("PLAN_LIMIT_REACHED");
        assertThat(refused.<String>json("$.error.message")).doesNotContain("plan");
    }

    @Test
    void overTheDocumentLimitUploadsAreRefused() throws SQLException {
        // The seeded document already fills a one-document plan.
        assignPlan(tenants.techStore(), limitedPlan(100, 1_000_000, 100, 1));
        Response upload = uploadFile(base(tenants.techStore()) + "/knowledge/documents", "faq.md",
                ("faq " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8), tenants.ownerA());
        assertThat(upload.status()).isEqualTo(403);
        assertThat(upload.errorCode()).isEqualTo("PLAN_LIMIT_REACHED");
    }

    // --- analytics ----------------------------------------------------------------------------

    @Test
    void analyticsCountOnlyTheWorkspacesOwnConversations() {
        String answered = session(widgetKeyA);
        customerSays(answered, "Refunds?");
        String escalated = session(widgetKeyA);
        assertThat(send("POST", "/api/v1/public/conversation/handoff", null, escalated).status()).isEqualTo(200);
        session(widgetKeyA);

        Response a = get(base(tenants.techStore()) + "/analytics?days=7", tenants.ownerA());
        assertThat(a.status()).as(a.body()).isEqualTo(200);
        assertThat(a.<Integer>json("$.conversations.total")).isEqualTo(3);
        assertThat(a.<Integer>json("$.conversations.resolved_by_ai")).isEqualTo(1);
        assertThat(a.<Integer>json("$.conversations.escalated")).isEqualTo(1);
        assertThat(a.<Integer>json("$.conversations.unanswered")).isEqualTo(1);
        assertThat(a.<List<Object>>json("$.daily")).hasSize(7);
        assertThat(a.<Integer>json("$.queue.open")).isEqualTo(1);
        assertThat(a.<Integer>json("$.queue.unassigned")).isEqualTo(1);
        assertThat(a.<String>json("$.intents[0].key")).isEqualTo("question");
        assertThat(a.<String>json("$.escalation_reasons[0].key")).isEqualTo("CUSTOMER_REQUEST");

        Response b = get(base(tenants.hotelPro()) + "/analytics?days=7", tenants.ownerB());
        assertThat(b.<Integer>json("$.conversations.total")).isZero();
        assertThat(b.<Integer>json("$.queue.open")).isZero();
        assertThat(b.<List<Object>>json("$.intents")).isEmpty();

        assertThat(get(base(tenants.techStore()) + "/analytics", tenants.ownerB()).status()).isEqualTo(404);
        assertThat(get(base(tenants.techStore()) + "/analytics", tenants.agentA().session()).status()).isEqualTo(403);
        Response badRange = get(base(tenants.techStore()) + "/analytics?days=12", tenants.ownerA());
        assertThat(badRange.errorCode()).isEqualTo("INVALID_RANGE");
    }

    @Test
    void supervisorsSeeAnalytics() {
        Member supervisor = addMember(tenants.ownerA(), tenants.techStore(), "analytics-supervisor", "SUPERVISOR");
        assertThat(get(base(tenants.techStore()) + "/analytics?days=30", supervisor.session()).status()).isEqualTo(200);
        assertThat(get(base(tenants.techStore()) + "/usage", supervisor.session()).status()).isEqualTo(403);
    }

    // --- audit coverage -----------------------------------------------------------------------

    @Test
    void ticketActionsAreAuditedAndTheLogIsTenantScoped() throws SQLException {
        String token = session(widgetKeyA);
        send("POST", "/api/v1/public/conversation/handoff", null, token);
        UUID conversation = conversationOf(token);
        String ticket = ticketOf(conversation);
        assertThat(send("PUT", base(tenants.techStore()) + "/tickets/" + ticket + "/assignee",
                        "{\"membership_id\": \"" + tenants.agentA().memberId() + "\"}", tenants.ownerA().accessToken())
                .status())
                .isEqualTo(200);
        int version = count("SELECT version FROM conversations WHERE id = ?", conversation);
        assertThat(send("POST", base(tenants.techStore()) + "/conversations/" + conversation + "/takeover",
                        "{\"expected_version\": " + version + "}", tenants.ownerA().accessToken())
                .status())
                .isEqualTo(200);
        assertThat(post(base(tenants.techStore()) + "/tickets/" + ticket + "/resolve", "{\"outcome\": \"SOLVED\"}",
                        tenants.ownerA())
                .status())
                .isEqualTo(200);

        Response log = get(base(tenants.techStore()) + "/audit-events", tenants.ownerA());
        List<String> actions = log.json("$.items[*].action");
        assertThat(actions).contains("CONVERSATION_TAKEN_OVER", "TICKET_RESOLVED", "TICKET_ASSIGNED");
        assertThat(log.<String>json("$.items[0].actor_name")).isNotBlank();

        List<String> otherActions = get(base(tenants.hotelPro()) + "/audit-events", tenants.ownerB()).json("$.items[*].action");
        assertThat(otherActions).doesNotContain("TICKET_RESOLVED");
        assertThat(get(base(tenants.techStore()) + "/audit-events", tenants.ownerB()).status()).isEqualTo(404);
    }

    // --- retention, deletion and export -------------------------------------------------------

    @Test
    void onlyOwnersSetRetentionWithinTheAllowedRange() {
        Member admin = addMember(tenants.ownerA(), tenants.techStore(), "retention-admin", "ADMIN");
        assertThat(send("PUT", base(tenants.techStore()) + "/retention", "{\"conversation_retention_days\": 90}",
                        admin.session().accessToken())
                .status())
                .isEqualTo(403);
        Response tooShort = send("PUT", base(tenants.techStore()) + "/retention", "{\"conversation_retention_days\": 7}",
                tenants.ownerA().accessToken());
        assertThat(tooShort.errorCode()).isEqualTo("INVALID_RETENTION");
        Response set = send("PUT", base(tenants.techStore()) + "/retention", "{\"conversation_retention_days\": 90}",
                tenants.ownerA().accessToken());
        assertThat(set.status()).isEqualTo(200);
        assertThat(get(base(tenants.techStore()), tenants.ownerA()).<Integer>json("$.conversation_retention_days"))
                .isEqualTo(90);
        assertThat(send("PUT", base(tenants.techStore()) + "/retention", "{\"conversation_retention_days\": null}",
                        tenants.ownerA().accessToken())
                .status())
                .isEqualTo(200);
        assertThat(get(base(tenants.techStore()), tenants.ownerA()).<Integer>json("$.conversation_retention_days")).isNull();
    }

    @Test
    void theRetentionJobDeletesOnlyExpiredConversationsOfThatWorkspace() throws SQLException {
        String oldToken = session(widgetKeyA);
        customerSays(oldToken, "Refunds?");
        UUID old = conversationOf(oldToken);
        String activeToken = session(widgetKeyA);
        send("POST", "/api/v1/public/conversation/handoff", null, activeToken);
        UUID oldButActive = conversationOf(activeToken);
        UUID recent = conversationOf(session(widgetKeyA));
        UUID otherTenant = conversationOf(session(publishedKey(tenants.hotelPro(), tenants.ownerB())));
        age(old, 40);
        age(oldButActive, 40);
        age(otherTenant, 40);
        send("PUT", base(tenants.techStore()) + "/retention", "{\"conversation_retention_days\": 30}",
                tenants.ownerA().accessToken());

        assertThat(retention.purgeExpired()).isEqualTo(1);

        assertThat(count("SELECT count(*) FROM conversations WHERE id = ?", old)).isZero();
        assertThat(count("SELECT count(*) FROM messages WHERE conversation_id = ?", old)).isZero();
        assertThat(count("SELECT count(*) FROM conversations WHERE id = ?", oldButActive))
                .as("a conversation with an active ticket is kept")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM conversations WHERE id = ?", recent)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM conversations WHERE id = ?", otherTenant))
                .as("the other workspace has no retention period")
                .isEqualTo(1);
        // Usage history survives the deletion of what it measured.
        assertThat(count("SELECT count(*) FROM usage_events WHERE reference_id = ?", old)).isEqualTo(1);
        List<String> actions = get(base(tenants.techStore()) + "/audit-events", tenants.ownerA()).json("$.items[*].action");
        assertThat(actions).contains("CONVERSATIONS_PURGED");
    }

    @Test
    void ownersDeleteTheirOwnConversationsOnly() throws SQLException {
        String token = session(widgetKeyA);
        customerSays(token, "Refunds?");
        send("POST", "/api/v1/public/conversation/handoff", null, token);
        UUID conversation = conversationOf(token);
        post(base(tenants.techStore()) + "/tickets/" + ticketOf(conversation) + "/resolve", "{\"outcome\": \"SOLVED\"}",
                tenants.ownerA());

        assertThat(delete(base(tenants.hotelPro()) + "/conversations/" + conversation, tenants.ownerB()).status())
                .as("another workspace's conversation is not found")
                .isEqualTo(404);
        assertThat(delete(base(tenants.techStore()) + "/conversations/" + conversation, tenants.ownerB()).status())
                .isEqualTo(404);
        assertThat(delete(base(tenants.techStore()) + "/conversations/" + conversation, tenants.agentA().session())
                        .status())
                .isEqualTo(403);
        assertThat(count("SELECT count(*) FROM conversations WHERE id = ?", conversation)).isEqualTo(1);

        assertThat(delete(base(tenants.techStore()) + "/conversations/" + conversation, tenants.ownerA()).status())
                .isEqualTo(204);
        assertThat(count("SELECT count(*) FROM conversations WHERE id = ?", conversation)).isZero();
        assertThat(count("SELECT count(*) FROM tickets WHERE conversation_id = ?", conversation)).isZero();
        // The widget session for a deleted conversation no longer works.
        assertThat(send("GET", "/api/v1/public/conversation", null, token).status()).isEqualTo(404);
    }

    @Test
    void theDeleteFunctionActsOnlyInsideTheBoundWorkspace() throws SQLException {
        UUID conversation = conversationOf(session(widgetKeyA));
        try (Connection connection = appConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.executeQuery("SELECT delete_conversation_data('" + conversation + "')"))
                        .hasMessageContaining("no workspace bound");
            }
            connection.rollback();
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT set_config('app.tenant_id', '" + tenants.hotelPro() + "', true)");
                ResultSet result = statement.executeQuery("SELECT delete_conversation_data('" + conversation + "')");
                result.next();
                assertThat(result.getBoolean(1)).isFalse();
            }
            connection.commit();
        }
        assertThat(count("SELECT count(*) FROM conversations WHERE id = ?", conversation)).isEqualTo(1);
    }

    @Test
    void ownersExportTheirWorkspaceWithoutSecrets() {
        String token = session(widgetKeyA);
        customerSays(token, "Refunds?");
        Member admin = addMember(tenants.ownerA(), tenants.techStore(), "export-admin", "ADMIN");
        assertThat(get(base(tenants.techStore()) + "/export", admin.session()).status()).isEqualTo(403);
        assertThat(get(base(tenants.techStore()) + "/export", tenants.ownerB()).status()).isEqualTo(404);

        Response export = get(base(tenants.techStore()) + "/export", tenants.ownerA());
        assertThat(export.status()).as(export.body()).isEqualTo(200);
        assertThat(export.header("Content-Disposition")).startsWith("attachment;");
        assertThat(export.<String>json("$.workspace.name")).isEqualTo("TechStore");
        assertThat(export.<List<Object>>json("$.conversations")).hasSize(1);
        assertThat(export.<String>json("$.conversations[0].messages[0].body")).isEqualTo("Refunds?");
        assertThat(export.<List<Object>>json("$.members")).hasSize(3);
        assertThat(export.body()).doesNotContain("password_hash", "token_hash", "HotelPro");
        List<String> actions = get(base(tenants.techStore()) + "/audit-events", tenants.ownerA()).json("$.items[*].action");
        assertThat(actions).contains("WORKSPACE_EXPORTED");
    }

    // --- platform administration --------------------------------------------------------------

    @Test
    void platformEndpointsAreInvisibleToEveryoneElse() {
        assertThat(get("/api/v1/platform/access", tenants.ownerA()).<Boolean>json("$.platform_admin")).isFalse();
        assertThat(get("/api/v1/platform/tenants", tenants.ownerA()).status()).isEqualTo(404);
        assertThat(post("/api/v1/platform/tenants/" + tenants.hotelPro() + "/suspend", "{\"reason\": \"x\"}", tenants.ownerA())
                        .status())
                .isEqualTo(404);
        assertThat(send("GET", "/api/v1/platform/tenants", null, null).status()).isEqualTo(401);
    }

    @Test
    void theFlagAloneGrantsNoCrossTenantAccess() throws SQLException {
        try (Connection connection = appConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT set_config('app.platform_admin', 'on', true)");
                statement.execute("SELECT set_config('app.user_id', '" + tenants.ownerA().userId() + "', true)");
                ResultSet result = statement.executeQuery("SELECT count(*) FROM tenants WHERE id = '" + tenants.hotelPro() + "'");
                result.next();
                assertThat(result.getInt(1)).isZero();
                result = statement.executeQuery("SELECT count(*) FROM usage_events WHERE tenant_id = '" + tenants.hotelPro() + "'");
                result.next();
                assertThat(result.getInt(1)).isZero();
            }
            connection.rollback();
        }
    }

    @Test
    void aPlatformAdminSuspendsReactivatesAndChangesPlansWithAnAuditTrail() throws SQLException {
        Session operator = newUser("platform-operator");
        grantPlatformAdmin(operator);
        String token = session(widgetKeyA);
        assertThat(get("/api/v1/platform/access", operator).<Boolean>json("$.platform_admin")).isTrue();

        Response list = get("/api/v1/platform/tenants?query=techstore", operator);
        assertThat(list.status()).as(list.body()).isEqualTo(200);
        assertThat(list.<List<String>>json("$.items[*].id")).contains(tenants.techStore().toString());
        assertThat(list.body()).doesNotContain("Refunds", "email");

        Response suspended = post("/api/v1/platform/tenants/" + tenants.techStore() + "/suspend",
                "{\"reason\": \"Unpaid invoice\"}", operator);
        assertThat(suspended.status()).as(suspended.body()).isEqualTo(200);
        assertThat(suspended.<String>json("$.status")).isEqualTo("SUSPENDED");

        // Dashboard, widget configuration and existing widget sessions are all refused.
        Response dashboard = get(base(tenants.techStore()), tenants.ownerA());
        assertThat(dashboard.status()).isEqualTo(403);
        assertThat(dashboard.errorCode()).isEqualTo("WORKSPACE_SUSPENDED");
        assertThat(send("GET", "/api/v1/public/widgets/" + widgetKeyA + "/config", null, null, "Origin", SITE).status())
                .isEqualTo(404);
        Response history = send("GET", "/api/v1/public/conversation", null, token);
        assertThat(history.status()).isEqualTo(403);
        assertThat(history.errorCode()).isEqualTo("WIDGET_UNAVAILABLE");
        // Members still see the workspace in their list, marked suspended with the reason.
        Response mine = get("/api/v1/workspaces", tenants.ownerA());
        assertThat(mine.<String>json("$[0].status")).isEqualTo("SUSPENDED");
        assertThat(mine.<String>json("$[0].suspended_reason")).isEqualTo("Unpaid invoice");
        // The other workspace is unaffected.
        assertThat(get(base(tenants.hotelPro()), tenants.ownerB()).status()).isEqualTo(200);

        assertThat(post("/api/v1/platform/tenants/" + tenants.techStore() + "/reactivate", null, operator).status())
                .isEqualTo(200);
        assertThat(get(base(tenants.techStore()), tenants.ownerA()).status()).isEqualTo(200);

        Response plan = send("PUT", "/api/v1/platform/tenants/" + tenants.techStore() + "/plan",
                "{\"plan_code\": \"STARTER\"}", operator.accessToken());
        assertThat(plan.<String>json("$.plan_code")).isEqualTo("STARTER");
        assertThat(get(base(tenants.techStore()) + "/usage", tenants.ownerA()).<String>json("$.plan.code")).isEqualTo("STARTER");
        Response unknown = send("PUT", "/api/v1/platform/tenants/" + tenants.techStore() + "/plan",
                "{\"plan_code\": \"PLATINUM\"}", operator.accessToken());
        assertThat(unknown.errorCode()).isEqualTo("UNKNOWN_PLAN");

        List<String> platformLog = get("/api/v1/platform/audit-events", operator).json("$.items[*].action");
        assertThat(platformLog).contains(
                "PLATFORM_WORKSPACE_SUSPENDED", "PLATFORM_WORKSPACE_REACTIVATED", "PLATFORM_PLAN_CHANGED");
        // The workspace's owners see what the operator did in their own log.
        List<String> workspaceLog = get(base(tenants.techStore()) + "/audit-events", tenants.ownerA()).json("$.items[*].action");
        assertThat(workspaceLog).contains("PLATFORM_WORKSPACE_SUSPENDED", "PLATFORM_PLAN_CHANGED");
        List<String> otherLog = get(base(tenants.hotelPro()) + "/audit-events", tenants.ownerB()).json("$.items[*].action");
        assertThat(otherLog).doesNotContain("PLATFORM_WORKSPACE_SUSPENDED");
    }

    // --- helpers ------------------------------------------------------------------------------

    private static String base(UUID workspaceId) {
        return "/api/v1/workspaces/" + workspaceId;
    }

    private String publishWidget(UUID workspaceId, Session owner) {
        Response origins = send("PUT", base(workspaceId) + "/widget/allowed-origins",
                "{\"origins\": [\"" + SITE + "\"]}", owner.accessToken());
        assertThat(origins.status()).as(origins.body()).isEqualTo(200);
        return get(base(workspaceId) + "/widget", owner).json("$.public_key");
    }

    private String publishedKey(UUID workspaceId, Session owner) {
        return get(base(workspaceId) + "/widget", owner).json("$.public_key");
    }

    private String session(String widgetKey) {
        Response response = send("POST", "/api/v1/public/widgets/" + widgetKey + "/sessions", null, null, "Origin", SITE);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json("$.token");
    }

    private Response customerSays(String token, String text) {
        return send("POST", "/api/v1/public/conversation/messages", "{\"text\": \"" + text + "\"}", token,
                "Idempotency-Key", "k-" + UUID.randomUUID());
    }

    private static UUID conversationOf(String token) {
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        return UUID.fromString(JsonPath.read(payload, "$.conversation_id"));
    }

    private AiAnswer answered() {
        return new AiAnswer("ANSWERED", "Refunds are accepted within 30 days.", "question", false, null,
                List.of(new Citation(chunkA, documentA, "policy.md", null, null, "Refunds are accepted within 30 days.")),
                List.of(new RetrievalHit(chunkA, documentA, "policy.md", 0.8, true)),
                "fake", "servora-fake-llm", "v", "test-model", 0.45, 5, 10, 5);
    }

    private static UUID seedChunk(UUID tenantId, UUID documentId) throws SQLException {
        UUID chunkId = UUID.randomUUID();
        try (Connection connection = ownerConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO documents (id, tenant_id, source_id, lineage_id, version, original_filename,
                                           storage_key, detected_mime, size_bytes, content_hash, status)
                    SELECT ?, ?, s.id, ?, 1, 'policy.md', ?, 'text/markdown', 10, ?, 'READY'
                    FROM knowledge_sources s WHERE s.tenant_id = ?
                    """)) {
                statement.setObject(1, documentId);
                statement.setObject(2, tenantId);
                statement.setObject(3, documentId);
                statement.setString(4, "k/" + documentId);
                statement.setString(5, UUID.randomUUID().toString());
                statement.setObject(6, tenantId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO document_chunks (id, tenant_id, document_id, chunk_index, content, embedding,
                                                 embedding_model, embedding_revision, embedding_dimension)
                    VALUES (?, ?, ?, 0, 'Refunds are accepted within 30 days.', '[0.1,0.2,0.3]'::vector, 'test-model', 'r1', 3)
                    """)) {
                statement.setObject(1, chunkId);
                statement.setObject(2, tenantId);
                statement.setObject(3, documentId);
                statement.executeUpdate();
            }
        }
        return chunkId;
    }

    /** A plan of its own, so tests never change the shared Free plan. */
    private static String limitedPlan(int aiRequests, long tokens, int conversations, int documents) throws SQLException {
        String code = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
        try (Connection connection = ownerConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO plans (code, name, monthly_ai_requests, monthly_tokens, monthly_conversations,
                                           max_documents, sort_order)
                        VALUES (?, 'Test', ?, ?, ?, ?, 99)
                        """)) {
            statement.setString(1, code);
            statement.setInt(2, aiRequests);
            statement.setLong(3, tokens);
            statement.setInt(4, conversations);
            statement.setInt(5, documents);
            statement.executeUpdate();
        }
        return code;
    }

    private static void assignPlan(UUID tenantId, String code) throws SQLException {
        try (Connection connection = ownerConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE subscriptions SET plan_code = ? WHERE tenant_id = ?")) {
            statement.setString(1, code);
            statement.setObject(2, tenantId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private static void grantPlatformAdmin(Session user) throws SQLException {
        try (Connection connection = ownerConnection();
                PreparedStatement statement = connection.prepareStatement("INSERT INTO platform_admins (user_id) VALUES (?)")) {
            statement.setObject(1, user.userId());
            statement.executeUpdate();
        }
    }

    private static void age(UUID conversationId, int days) throws SQLException {
        Timestamp then = Timestamp.from(Instant.now().minus(Duration.ofDays(days)));
        try (Connection connection = ownerConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE conversations SET created_at = ?, last_message_at = ? WHERE id = ?")) {
            statement.setTimestamp(1, then);
            statement.setTimestamp(2, then);
            statement.setObject(3, conversationId);
            statement.executeUpdate();
        }
    }

    private static String ticketOf(UUID conversationId) throws SQLException {
        try (Connection connection = ownerConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT id FROM tickets WHERE conversation_id = ?")) {
            statement.setObject(1, conversationId);
            ResultSet row = statement.executeQuery();
            return row.next() ? row.getString(1) : null;
        }
    }

    private static int count(String sql, Object parameter) throws SQLException {
        try (Connection connection = ownerConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter);
            ResultSet row = statement.executeQuery();
            row.next();
            return row.getInt(1);
        }
    }
}
