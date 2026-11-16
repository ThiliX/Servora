package com.servora.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.servora.support.IntegrationTestBase;
import com.servora.support.TwoTenants;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** FR-008, FR-009, FR-011, FR-012: assistant configuration with versions, widget key, settings and origins. */
class AssistantAndWidgetIntegrationTest extends IntegrationTestBase {

    private static final String ASSISTANT_BODY = """
            {"name": "%s", "welcome_message": "Welcome!", "fallback_message": "Let me get a colleague.",
             "language": "en", "tone": "PROFESSIONAL", "automation_enabled": true, "confidence_policy": "BALANCED"}""";

    private static TwoTenants tenants;

    @BeforeEach
    void createTenantsOnce() {
        if (tenants == null) {
            tenants = TwoTenants.create(this);
        }
    }

    private static String assistant(UUID workspaceId) {
        return "/api/v1/workspaces/" + workspaceId + "/assistant";
    }

    private static String widget(UUID workspaceId) {
        return "/api/v1/workspaces/" + workspaceId + "/widget";
    }

    private Response put(String path, String body, Session session) {
        return send("PUT", path, body, session.accessToken());
    }

    @Test
    void aNewWorkspaceStartsWithADefaultAssistantAndWidget() {
        Session owner = newUser("defaults");
        UUID workspaceId = createWorkspace(owner, "Defaults");

        Response assistantResponse = get(assistant(workspaceId), owner);
        Response widgetResponse = get(widget(workspaceId), owner);

        assertThat(assistantResponse.status()).isEqualTo(200);
        assertThat(assistantResponse.<String>json("$.name")).isEqualTo("Defaults Assistant");
        assertThat(assistantResponse.<Integer>json("$.current_version")).isEqualTo(1);
        assertThat(assistantResponse.<String>json("$.confidence_policy")).isEqualTo("STRICT");
        assertThat(widgetResponse.status()).isEqualTo(200);
        assertThat(widgetResponse.<String>json("$.public_key")).startsWith("wk_");
        assertThat(widgetResponse.<String>json("$.status")).isEqualTo("ACTIVE");
        assertThat(widgetResponse.<List<String>>json("$.allowed_origins")).isEmpty();
        assertThat(get(assistant(workspaceId) + "/versions", owner).<Integer>json("$.total"))
                .isEqualTo(1);
    }

    @Test
    void updatingTheAssistantRecordsANewImmutableVersion() {
        Session owner = newUser("versions");
        UUID workspaceId = createWorkspace(owner, "Versions");

        Response first = put(assistant(workspaceId), ASSISTANT_BODY.formatted("Helper One"), owner);
        Response second = put(assistant(workspaceId), ASSISTANT_BODY.formatted("Helper Two"), owner);

        assertThat(first.status()).as(first.body()).isEqualTo(200);
        assertThat(first.<Integer>json("$.current_version")).isEqualTo(2);
        assertThat(second.<Integer>json("$.current_version")).isEqualTo(3);
        assertThat(second.<String>json("$.name")).isEqualTo("Helper Two");
        assertThat(second.<String>json("$.tone")).isEqualTo("PROFESSIONAL");

        Response versions = get(assistant(workspaceId) + "/versions", owner);
        assertThat(versions.<List<Integer>>json("$.items[*].version")).containsExactly(3, 2, 1);
        // Earlier snapshots keep the configuration as it was at the time.
        assertThat(versions.<List<String>>json("$.items[?(@.version == 2)].config.name"))
                .containsExactly("Helper One");
        assertThat(versions.<List<String>>json("$.items[?(@.version == 3)].config.name"))
                .containsExactly("Helper Two");
        assertThat(versions.<List<String>>json("$.items[?(@.version == 3)].created_by_user_id"))
                .containsExactly(owner.userId().toString());
    }

    @Test
    void invalidAssistantConfigurationIsRejected() {
        Response badTone = put(
                assistant(tenants.techStore()),
                ASSISTANT_BODY.formatted("X").replace("PROFESSIONAL", "SARCASTIC"),
                tenants.ownerA());
        Response blankName = put(assistant(tenants.techStore()), ASSISTANT_BODY.formatted(" "), tenants.ownerA());

        assertThat(badTone.status()).isEqualTo(400);
        assertThat(blankName.status()).isEqualTo(400);
        assertThat(blankName.errorCode()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void agentsCannotReadOrChangeAssistantAndWidgetConfiguration() {
        Session agent = tenants.agentA().session();

        assertThat(get(assistant(tenants.techStore()), agent).status()).isEqualTo(403);
        assertThat(put(assistant(tenants.techStore()), ASSISTANT_BODY.formatted("Nope"), agent).status())
                .isEqualTo(403);
        assertThat(get(widget(tenants.techStore()), agent).status()).isEqualTo(403);
        assertThat(patch(widget(tenants.techStore()), "{\"title\": \"Nope\"}", agent).status())
                .isEqualTo(403);
        assertThat(post(widget(tenants.techStore()) + "/rotate-key", null, agent).status())
                .isEqualTo(403);
        assertThat(put(widget(tenants.techStore()) + "/allowed-origins", "{\"origins\": []}", agent)
                        .status())
                .isEqualTo(403);
    }

    @Test
    void assistantAndWidgetOfAnotherTenantAreNotReachable() {
        String keyB = get(widget(tenants.hotelPro()), tenants.ownerB()).json("$.public_key");

        assertThat(get(assistant(tenants.hotelPro()), tenants.ownerA()).status()).isEqualTo(404);
        assertThat(put(assistant(tenants.hotelPro()), ASSISTANT_BODY.formatted("Hijacked"), tenants.ownerA())
                        .status())
                .isEqualTo(404);
        assertThat(get(assistant(tenants.hotelPro()) + "/versions", tenants.ownerA()).status())
                .isEqualTo(404);
        assertThat(get(widget(tenants.hotelPro()), tenants.ownerA()).status()).isEqualTo(404);
        assertThat(patch(widget(tenants.hotelPro()), "{\"status\": \"DISABLED\"}", tenants.ownerA())
                        .status())
                .isEqualTo(404);
        assertThat(post(widget(tenants.hotelPro()) + "/rotate-key", null, tenants.ownerA()).status())
                .isEqualTo(404);
        assertThat(put(
                                widget(tenants.hotelPro()) + "/allowed-origins",
                                "{\"origins\": [\"https://evil.test\"]}",
                                tenants.ownerA())
                        .status())
                .isEqualTo(404);

        Response widgetB = get(widget(tenants.hotelPro()), tenants.ownerB());
        assertThat(widgetB.<String>json("$.public_key")).isEqualTo(keyB);
        assertThat(widgetB.<String>json("$.status")).isEqualTo("ACTIVE");
        assertThat(widgetB.<List<String>>json("$.allowed_origins")).doesNotContain("https://evil.test");
        assertThat(get(assistant(tenants.hotelPro()), tenants.ownerB()).<String>json("$.name"))
                .isEqualTo("HotelPro Assistant");
        // Each tenant has its own key, and A's configuration contains nothing of B's.
        assertThat(get(widget(tenants.techStore()), tenants.ownerA()).body()).doesNotContain(keyB);
    }

    @Test
    void widgetSettingsCanBeChangedAndDisabled() {
        Response updated = patch(
                widget(tenants.techStore()),
                """
                {"title": "Chat with TechStore", "theme_color": "#0EA5E9", "position": "BOTTOM_LEFT",
                 "show_branding": false, "status": "DISABLED"}""",
                tenants.ownerA());

        assertThat(updated.status()).as(updated.body()).isEqualTo(200);
        assertThat(updated.<String>json("$.title")).isEqualTo("Chat with TechStore");
        assertThat(updated.<String>json("$.theme_color")).isEqualTo("#0ea5e9");
        assertThat(updated.<String>json("$.position")).isEqualTo("BOTTOM_LEFT");
        assertThat(updated.<Boolean>json("$.show_branding")).isFalse();
        assertThat(updated.<String>json("$.status")).isEqualTo("DISABLED");

        assertThat(patch(widget(tenants.techStore()), "{\"theme_color\": \"red\"}", tenants.ownerA())
                        .status())
                .isEqualTo(400);
        patch(widget(tenants.techStore()), "{\"status\": \"ACTIVE\"}", tenants.ownerA());
    }

    @Test
    void rotatingTheKeyReplacesIt() {
        String before = get(widget(tenants.techStore()), tenants.ownerA()).json("$.public_key");

        Response rotated = post(widget(tenants.techStore()) + "/rotate-key", null, tenants.ownerA());

        assertThat(rotated.status()).isEqualTo(200);
        assertThat(rotated.<String>json("$.public_key")).startsWith("wk_").isNotEqualTo(before);
        assertThat(get(widget(tenants.techStore()), tenants.ownerA()).body()).doesNotContain(before);
    }

    @Test
    void allowedOriginsAreStoredCanonicallyAndReplacedAsAWhole() {
        Session owner = tenants.ownerA();
        String path = widget(tenants.techStore()) + "/allowed-origins";

        Response first = put(
                path,
                """
                {"origins": ["HTTPS://Shop.Example.com/", "https://shop.example.com:443", "http://localhost:5173"]}""",
                owner);
        assertThat(first.status()).as(first.body()).isEqualTo(200);
        assertThat(first.<List<String>>json("$.allowed_origins"))
                .containsExactly("http://localhost:5173", "https://shop.example.com");

        Response replaced = put(path, "{\"origins\": [\"https://www.example.com\"]}", owner);
        assertThat(replaced.<List<String>>json("$.allowed_origins")).containsExactly("https://www.example.com");
    }

    @Test
    void wildcardInsecureAndMalformedOriginsAreRejectedWithoutChangingTheList() {
        Session owner = tenants.ownerB();
        String path = widget(tenants.hotelPro()) + "/allowed-origins";
        put(path, "{\"origins\": [\"https://hotel.example.com\"]}", owner);

        for (String bad : List.of(
                "https://*.example.com",
                "http://hotel.example.com",
                "https://hotel.example.com/booking",
                "hotel.example.com",
                "https://user@hotel.example.com")) {
            Response response = put(path, "{\"origins\": [\"https://ok.example.com\", \"" + bad + "\"]}", owner);
            assertThat(response.status()).as(bad).isEqualTo(400);
            assertThat(response.errorCode()).isEqualTo("INVALID_ORIGIN");
        }
        assertThat(get(widget(tenants.hotelPro()), owner).<List<String>>json("$.allowed_origins"))
                .containsExactly("https://hotel.example.com");
    }

    @Test
    void configurationChangesAreAudited() {
        Session owner = newUser("audited");
        UUID workspaceId = createWorkspace(owner, "Audited");
        put(assistant(workspaceId), ASSISTANT_BODY.formatted("Audited"), owner);
        post(widget(workspaceId) + "/rotate-key", null, owner);
        put(widget(workspaceId) + "/allowed-origins", "{\"origins\": [\"https://a.example.com\"]}", owner);

        Response audit = get("/api/v1/workspaces/" + workspaceId + "/audit-events", owner);

        assertThat(audit.<List<String>>json("$.items[*].action"))
                .contains("ASSISTANT_UPDATED", "WIDGET_KEY_ROTATED", "WIDGET_ORIGINS_REPLACED");
        // The public key itself is never written to the audit log.
        String key = get(widget(workspaceId), owner).json("$.public_key");
        assertThat(audit.body()).doesNotContain(key);
    }
}
