package com.servora.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.servora.ai.AiAnswer.Citation;
import com.servora.ai.AiAnswer.RetrievalHit;
import com.servora.support.IntegrationTestBase;
import com.servora.support.TwoTenants;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** FR-010, FR-029: the assistant playground, and the core API's own check of what the AI service returns. */
class PlaygroundIntegrationTest extends IntegrationTestBase {

    private static final String QUESTION = "{\"question\": \"What is the refund policy?\"}";

    private static TwoTenants tenants;
    private static UUID chunkA;
    private static UUID documentA;
    private static UUID chunkB;

    @Autowired
    private ScriptedAi ai;

    @BeforeEach
    void setUp() throws SQLException {
        if (tenants == null) {
            tenants = TwoTenants.create(this);
            documentA = UUID.randomUUID();
            chunkA = seedChunk(tenants.techStore(), documentA);
            chunkB = seedChunk(tenants.hotelPro(), UUID.randomUUID());
        }
        ai.script = null;
        ai.questions.clear();
    }

    private static String test(UUID workspaceId) {
        return "/api/v1/workspaces/" + workspaceId + "/assistant/test";
    }

    /** Inserts a READY document with one chunk, as the owner role. Returns the chunk id. */
    private static UUID seedChunk(UUID tenantId, UUID documentId) throws SQLException {
        UUID chunkId = UUID.randomUUID();
        try (Connection connection = ownerConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO documents (id, tenant_id, source_id, lineage_id, version, original_filename,
                                           storage_key, detected_mime, size_bytes, content_hash, status)
                    SELECT ?, ?, s.id, ?, 1, 'refunds.md', ?, 'text/markdown', 10, ?, 'READY'
                    FROM knowledge_sources s WHERE s.tenant_id = ?
                    """)) {
                statement.setObject(1, documentId);
                statement.setObject(2, tenantId);
                statement.setObject(3, documentId);
                statement.setString(4, "k/" + documentId);
                statement.setString(5, UUID.randomUUID().toString());
                statement.setObject(6, tenantId);
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO document_chunks (id, tenant_id, document_id, chunk_index, content, embedding,
                                                 embedding_model, embedding_revision, embedding_dimension)
                    VALUES (?, ?, ?, 0, 'Refunds within 30 days.', '[0.1,0.2,0.3]'::vector, 'test-model', 'r1', 3)
                    """)) {
                statement.setObject(1, chunkId);
                statement.setObject(2, tenantId);
                statement.setObject(3, documentId);
                statement.executeUpdate();
            }
        }
        return chunkId;
    }

    private static AiAnswer answered(UUID citedChunk, List<RetrievalHit> retrieval) {
        return new AiAnswer(
                "ANSWERED",
                "Refunds are accepted within 30 days.",
                "question",
                false,
                null,
                List.of(new Citation(citedChunk, documentA, "refunds.md", 1, null, "Refunds within 30 days.")),
                retrieval,
                "fake",
                "servora-fake-llm",
                "2026-11-17.1",
                "test-model",
                0.35,
                12,
                100,
                20);
    }

    private static List<RetrievalHit> hits(UUID chunkId, boolean used) {
        return List.of(new RetrievalHit(chunkId, documentA, "refunds.md", 0.82, used));
    }

    private static String storedOutcome(UUID tenantId, String interactionId) throws SQLException {
        try (Connection connection = ownerConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT outcome FROM ai_interactions WHERE tenant_id = ? AND id = ?")) {
            statement.setObject(1, tenantId);
            statement.setObject(2, UUID.fromString(interactionId));
            ResultSet result = statement.executeQuery();
            return result.next() ? result.getString(1) : null;
        }
    }

    @Test
    void anOwnerGetsAnAnswerWithCitationsAndTheInteractionIsRecorded() throws SQLException {
        ai.script = (tenantId, assistant, question) -> {
            assertThat(tenantId).isEqualTo(tenants.techStore());
            assertThat(assistant.name()).isEqualTo("TechStore Assistant");
            return answered(chunkA, hits(chunkA, true));
        };

        Response response = post(test(tenants.techStore()), QUESTION, tenants.ownerA());

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.<String>json("$.outcome")).isEqualTo("ANSWERED");
        assertThat(response.<String>json("$.answer")).contains("30 days");
        assertThat(response.<String>json("$.citations[0].chunk_id")).isEqualTo(chunkA.toString());
        assertThat(response.<String>json("$.citations[0].filename")).isEqualTo("refunds.md");
        assertThat(response.<Double>json("$.retrieval[0].score")).isEqualTo(0.82);
        assertThat(response.<String>json("$.model")).isEqualTo("servora-fake-llm");
        assertThat(response.<String>json("$.prompt_version")).isEqualTo("2026-11-17.1");
        assertThat(ai.questions).containsExactly(tenants.techStore() + ":What is the refund policy?");

        try (Connection connection = ownerConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT tenant_id, outcome, question, cited_chunk_ids::text, created_by_user_id,
                               assistant_version, retrieval->0->>'chunk_id', input_tokens
                        FROM ai_interactions WHERE id = ?
                        """)) {
            statement.setObject(1, UUID.fromString(response.<String>json("$.id")));
            ResultSet row = statement.executeQuery();
            assertThat(row.next()).isTrue();
            assertThat(row.getObject(1)).isEqualTo(tenants.techStore());
            assertThat(row.getString(2)).isEqualTo("ANSWERED");
            assertThat(row.getString(3)).isEqualTo("What is the refund policy?");
            assertThat(row.getString(4)).isEqualTo("{" + chunkA + "}");
            assertThat(row.getObject(5)).isEqualTo(tenants.ownerA().userId());
            assertThat(row.getInt(6)).isEqualTo(1);
            assertThat(row.getString(7)).isEqualTo(chunkA.toString());
            assertThat(row.getInt(8)).isEqualTo(100);
        }
    }

    @Test
    void aCitationOfAnotherTenantsChunkIsRejectedAndNothingOfItIsReturned() throws SQLException {
        // A compromised or buggy AI service answers tenant A with tenant B's chunk.
        ai.script = (tenantId, assistant, question) -> answered(chunkB, hits(chunkB, true));

        Response response = post(test(tenants.techStore()), QUESTION, tenants.ownerA());

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.<String>json("$.outcome")).isEqualTo("INVALID_CITATION");
        assertThat(response.<String>json("$.answer")).doesNotContain("30 days");
        assertThat(response.<Boolean>json("$.needs_escalation")).isTrue();
        assertThat(response.<List<Object>>json("$.citations")).isEmpty();
        assertThat(response.<List<Object>>json("$.retrieval")).isEmpty();
        assertThat(response.body()).doesNotContain(chunkB.toString());
        assertThat(storedOutcome(tenants.techStore(), response.json("$.id"))).isEqualTo("INVALID_CITATION");
    }

    @Test
    void aCitationThatWasNotGivenToTheModelIsRejected() {
        UUID unknown = UUID.randomUUID();
        ai.script = (tenantId, assistant, question) -> answered(chunkA, hits(chunkA, false));
        Response notUsed = post(test(tenants.techStore()), QUESTION, tenants.ownerA());
        ai.script = (tenantId, assistant, question) -> answered(unknown, hits(chunkA, true));
        Response invented = post(test(tenants.techStore()), QUESTION, tenants.ownerA());
        ai.script = (tenantId, assistant, question) -> new AiAnswer(
                "ANSWERED", "Trust me.", "question", false, null, List.of(), hits(chunkA, true),
                "fake", "m", "v", "e", 0.35, 1, 1, 1);
        Response uncited = post(test(tenants.techStore()), QUESTION, tenants.ownerA());

        for (Response response : List.of(notUsed, invented, uncited)) {
            assertThat(response.<String>json("$.outcome")).isEqualTo("INVALID_CITATION");
            assertThat(response.<List<Object>>json("$.citations")).isEmpty();
            assertThat(response.<String>json("$.answer")).isEqualTo("I am not sure about that. Would you like to talk to a member of our team?");
        }
    }

    @Test
    void whenTheAiServiceIsDownTheFallbackMessageIsReturnedAndRecorded() throws SQLException {
        ai.script = (tenantId, assistant, question) -> {
            throw new AiClient.AiUnavailableException("connection refused", null);
        };
        Response down = post(test(tenants.techStore()), QUESTION, tenants.ownerA());
        ai.script = (tenantId, assistant, question) -> new AiAnswer(
                "SOMETHING_NEW", "?", null, false, null, null, null, "fake", "m", "v", "e", 0, 0, 0, 0);
        Response unknownOutcome = post(test(tenants.techStore()), QUESTION, tenants.ownerA());

        for (Response response : List.of(down, unknownOutcome)) {
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.<String>json("$.outcome")).isEqualTo("SERVICE_UNAVAILABLE");
            assertThat(response.<Boolean>json("$.needs_escalation")).isTrue();
            assertThat(response.<String>json("$.answer")).startsWith("I am not sure about that.");
            assertThat(response.body()).doesNotContain("connection refused");
            assertThat(storedOutcome(tenants.techStore(), response.json("$.id"))).isEqualTo("SERVICE_UNAVAILABLE");
        }
    }

    @Test
    void agentsAndOutsidersCannotUseThePlayground() {
        ai.script = (tenantId, assistant, question) -> answered(chunkA, hits(chunkA, true));

        assertThat(post(test(tenants.techStore()), QUESTION, tenants.agentA().session()).status()).isEqualTo(403);
        assertThat(post(test(tenants.techStore()), QUESTION, tenants.ownerB()).status()).isEqualTo(404);
        assertThat(send("POST", test(tenants.techStore()), QUESTION, null).status()).isEqualTo(401);
        assertThat(ai.questions).isEmpty();
    }

    @Test
    void theQuestionIsValidated() {
        Session owner = tenants.ownerA();

        assertThat(post(test(tenants.techStore()), "{\"question\": \"  \"}", owner).status()).isEqualTo(400);
        assertThat(post(test(tenants.techStore()), "{}", owner).status()).isEqualTo(400);
        String tooLong = "{\"question\": \"" + "x".repeat(2001) + "\"}";
        assertThat(post(test(tenants.techStore()), tooLong, owner).status()).isEqualTo(400);
        assertThat(ai.questions).isEmpty();
    }

    @Test
    void testQuestionsAreRateLimitedPerWorkspace() {
        Session owner = newUser("limited");
        UUID workspaceId = createWorkspace(owner, "Limited");
        ai.script = (tenantId, assistant, question) -> new AiAnswer(
                "NO_EVIDENCE", assistant.fallbackMessage(), null, true, "no_evidence", List.of(), List.of(),
                "fake", null, "v", "e", 0.45, 3, 0, 0);

        for (int i = 0; i < PLAYGROUND_TESTS_PER_HOUR; i++) {
            assertThat(post(test(workspaceId), QUESTION, owner).status()).isEqualTo(200);
        }
        Response limited = post(test(workspaceId), QUESTION, owner);

        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.errorCode()).isEqualTo("PLAYGROUND_RATE_LIMITED");
        assertThat(ai.questions).hasSize(PLAYGROUND_TESTS_PER_HOUR);
    }

    @Test
    void interactionsAreIsolatedByRowLevelSecurity() throws SQLException {
        ai.script = (tenantId, assistant, question) -> answered(chunkA, hits(chunkA, true));
        String id = post(test(tenants.techStore()), QUESTION, tenants.ownerA()).json("$.id");

        try (Connection app = appConnection()) {
            app.setAutoCommit(false);
            try (Statement statement = app.createStatement()) {
                assertThat(count(statement, "SELECT count(*) FROM ai_interactions")).isZero();
                statement.execute("SELECT set_config('app.tenant_id', '" + tenants.hotelPro() + "', true)");
                assertThat(count(statement, "SELECT count(*) FROM ai_interactions WHERE id = '" + id + "'")).isZero();
                // Tenant B's context cannot write a row for tenant A, and rows cannot be changed at all.
                assertThatThrownBy(() -> statement.executeUpdate("""
                                INSERT INTO ai_interactions (id, tenant_id, source, assistant_version, question, answer,
                                    outcome, needs_escalation, provider, prompt_version, embedding_model,
                                    evidence_threshold, latency_ms)
                                VALUES (gen_random_uuid(), '%s', 'PLAYGROUND', 1, 'q', 'a', 'ANSWERED', false, 'p', 'v',
                                    'e', 0, 0)
                                """.formatted(tenants.techStore())))
                        .hasMessageContaining("row-level security");
            }
            app.rollback();
            try (Statement statement = app.createStatement()) {
                statement.execute("SELECT set_config('app.tenant_id', '" + tenants.techStore() + "', true)");
                assertThat(count(statement, "SELECT count(*) FROM ai_interactions WHERE id = '" + id + "'")).isEqualTo(1);
                assertThatThrownBy(() -> statement.executeUpdate("UPDATE ai_interactions SET answer = 'x'"))
                        .hasMessageContaining("permission denied");
            }
            app.rollback();
        }
    }

    @Test
    void theAiRoleCanOnlyReadChunksOfTheBoundTenant() throws SQLException {
        try (Connection aiRole = aiConnection()) {
            aiRole.setAutoCommit(false);
            try (Statement statement = aiRole.createStatement()) {
                assertThat(count(statement, "SELECT count(*) FROM document_chunks")).isZero();
                statement.execute("SELECT set_config('app.tenant_id', '" + tenants.techStore() + "', true)");
                assertThat(count(statement, "SELECT count(*) FROM document_chunks WHERE id = '" + chunkA + "'"))
                        .isEqualTo(1);
                assertThat(count(statement, "SELECT count(*) FROM document_chunks WHERE id = '" + chunkB + "'"))
                        .isZero();
                assertThat(count(statement, "SELECT count(*) FROM documents WHERE status = 'READY'")).isEqualTo(1);
                ResultSet role = statement.executeQuery(
                        "SELECT rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb FROM pg_roles WHERE rolname = current_user");
                role.next();
                assertThat(role.getBoolean(1)).isFalse();
            }
            aiRole.rollback();
            for (String sql : List.of(
                    "UPDATE document_chunks SET content = 'x'",
                    "DELETE FROM document_chunks",
                    "SELECT storage_key FROM documents",
                    "UPDATE documents SET status = 'READY'",
                    "SELECT count(*) FROM users",
                    "SELECT count(*) FROM tenants",
                    "SELECT count(*) FROM ai_interactions",
                    "SELECT count(*) FROM ai_assistants",
                    "SELECT count(*) FROM outbox_events")) {
                try (Statement statement = aiRole.createStatement()) {
                    assertThatThrownBy(() -> statement.execute(sql)).as(sql).hasMessageContaining("permission denied");
                }
                aiRole.rollback();
            }
        }
    }

    private static long count(Statement statement, String sql) throws SQLException {
        ResultSet result = statement.executeQuery(sql);
        result.next();
        return result.getLong(1);
    }
}
