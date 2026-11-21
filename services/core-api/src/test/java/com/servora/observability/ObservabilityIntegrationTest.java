package com.servora.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.servora.ai.AiAnswer;
import com.servora.knowledge.OutboxProcessor;
import com.servora.support.IntegrationTestBase;
import com.servora.support.TwoTenants;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalManagementPort;

/** ADR-022: the management port, request and trace IDs, metrics without tenant data, the outbox trace. */
class ObservabilityIntegrationTest extends IntegrationTestBase {

    private static final String TRACE_ID = "^[0-9a-f]{32}$";
    private static TwoTenants tenants;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private ScriptedAi ai;

    @Autowired
    private OutboxProcessor outbox;

    @Autowired
    private OperationalMetrics operationalMetrics;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        if (tenants == null) {
            tenants = TwoTenants.create(this);
        }
    }

    private HttpResponse<String> management(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + path))
                        .timeout(Duration.ofSeconds(30))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String scrape() throws Exception {
        HttpResponse<String> response = management("/actuator/prometheus");
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    @Test
    void metricsAndHealthDetailsAreServedOnlyOnTheManagementPort() throws Exception {
        assertThat(management("/actuator/health").statusCode()).isEqualTo(200);
        assertThat(scrape()).contains("http_server_requests_seconds");

        // On the API port, only the two probes exist; the actuator is not reachable there.
        assertThat(get("/livez", null).status()).isEqualTo(200);
        assertThat(get("/readyz", null).status()).isEqualTo(200);
        assertThat(get("/actuator/prometheus", null).status()).isIn(401, 404);
        assertThat(get("/actuator/prometheus", tenants.ownerA()).status()).isEqualTo(404);
        assertThat(get("/actuator/health", null).status()).isIn(401, 404);
    }

    @Test
    void theRequestIdIsTheTraceIdAndAppearsInErrorBodies() {
        Response ok = get("/api/v1/workspaces", tenants.ownerA());
        Response missing = get("/api/v1/workspaces", null);

        assertThat(ok.header("X-Request-Id")).matches(TRACE_ID);
        assertThat(missing.header("X-Request-Id")).matches(TRACE_ID).isNotEqualTo(ok.header("X-Request-Id"));
        assertThat(missing.<String>json("$.error.trace_id")).isEqualTo(missing.header("X-Request-Id"));
    }

    @Test
    void anIncomingTraceContextIsContinued() {
        // Inside the network the API continues a caller's trace. The public gateway strips these
        // headers, so a browser cannot choose trace IDs or force sampling (ADR-022).
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        Response response = send(
                "GET", "/api/v1/workspaces", null, tenants.ownerA().accessToken(),
                "traceparent", "00-" + traceId + "-00f067aa0ba902b7-01");

        assertThat(response.header("X-Request-Id")).isEqualTo(traceId);
    }

    @Test
    void anUploadStoresItsTraceInTheOutboxAndDeliveryIsTimed() throws Exception {
        String document = "# Returns\n\nItems can be returned within 30 days.\n";
        Response upload = uploadFile(
                "/api/v1/workspaces/" + tenants.techStore() + "/knowledge/documents",
                "returns.md",
                document.getBytes(StandardCharsets.UTF_8),
                tenants.ownerA());
        assertThat(upload.status()).as(upload.body()).isEqualTo(202);
        UUID documentId = UUID.fromString(upload.json("$.id"));

        String traceParent;
        try (Connection connection = ownerConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT trace_parent FROM outbox_events WHERE tenant_id = ? AND aggregate_id = ?")) {
            statement.setObject(1, tenants.techStore());
            statement.setObject(2, documentId);
            ResultSet result = statement.executeQuery();
            assertThat(result.next()).isTrue();
            traceParent = result.getString(1);
        }
        assertThat(traceParent).startsWith("00-" + upload.header("X-Request-Id") + "-");

        for (int i = 0; i < 50 && outbox.processNext(); i++) {
            // Deliver everything that is due.
        }
        assertThat(scrape()).containsPattern("servora_outbox_delivery_seconds_count\\{[^}]*result=\"delivered\"");
    }

    @Test
    void aiAnswersAreCountedByOutcomeWithTokensAndNoTenantLabels() throws Exception {
        ai.script = (tenantId, assistant, question) -> new AiAnswer(
                "NO_EVIDENCE",
                assistant.fallbackMessage(),
                null,
                true,
                "no_evidence",
                List.of(),
                List.of(),
                "fake",
                null,
                "2026-11-17.1",
                "test-model",
                0.35,
                7,
                0,
                0);

        Response response = post(
                "/api/v1/workspaces/" + tenants.techStore() + "/assistant/test",
                "{\"question\":\"Do you sell gift cards?\"}",
                tenants.ownerA());
        assertThat(response.status()).as(response.body()).isEqualTo(200);

        String metrics = scrape();
        assertThat(metrics).containsPattern(
                "servora_ai_answers_seconds_count\\{[^}]*outcome=\"NO_EVIDENCE\"[^}]*source=\"PLAYGROUND\"");
        assertThat(metrics).contains("servora_ai_retrieval_chunks_count");
        // No workspace or user identifier ever becomes a metric label.
        assertThat(metrics)
                .doesNotContain(tenants.techStore().toString())
                .doesNotContain(tenants.hotelPro().toString())
                .doesNotContain(tenants.ownerA().userId().toString());
    }

    @Test
    void operationalGaugesCountDocumentsAndTheOutboxAcrossWorkspaces() throws Exception {
        operationalMetrics.refresh();

        String metrics = scrape();
        assertThat(metrics).contains("servora_outbox_oldest_pending_age_seconds");
        assertThat(metrics).contains("servora_documents_processing_oldest_age_seconds");
        assertThat(metrics).doesNotContain(tenants.techStore().toString());
    }

    @Test
    void theOperationalFunctionReturnsAggregatesOnly() throws SQLException {
        try (Connection connection = appConnection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT metric, label FROM operational_metrics()")) {
            ResultSet result = statement.executeQuery();
            while (result.next()) {
                assertThat(result.getString(1))
                        .isIn(
                                "documents",
                                "documents_processing_oldest_age_seconds",
                                "outbox_events",
                                "outbox_oldest_pending_age_seconds",
                                "tickets");
                assertThat(result.getString(2)).doesNotMatch("[0-9a-f-]{36}");
            }
        }
    }

    @Test
    void rateLimitRefusalsAreCountedByLimiterFamily() throws Exception {
        UUID workspace = tenants.hotelPro();
        ai.script = (tenantId, assistant, question) -> new AiAnswer(
                "NO_EVIDENCE", "Sorry", null, true, "no_evidence", List.of(), List.of(), "fake", null,
                "2026-11-17.1", "test-model", 0.35, 1, 0, 0);
        int refused = 0;
        for (int i = 0; i <= PLAYGROUND_TESTS_PER_HOUR + 1; i++) {
            Response response = post(
                    "/api/v1/workspaces/" + workspace + "/assistant/test", "{\"question\":\"Hi?\"}", tenants.ownerB());
            if (response.status() == 429) {
                refused++;
            }
        }
        assertThat(refused).isPositive();
        assertThat(scrape())
                .containsPattern("servora_rate_limit_rejections_total\\{[^}]*limiter=\"playground\"")
                .doesNotContain(workspace.toString());
    }
}
