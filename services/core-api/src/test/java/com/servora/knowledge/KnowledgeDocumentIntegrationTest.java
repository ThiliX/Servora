package com.servora.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.servora.support.IntegrationTestBase;
import com.servora.support.TwoTenants;
import java.nio.charset.StandardCharsets;
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

/**
 * FR-013 to FR-019 on the core API side: validated private uploads, the document lifecycle, the
 * outbox, and tenant isolation of documents and chunks for both the API role and the worker role.
 *
 * <p>The Python worker is not running here. Where a test needs processing to have happened, it
 * performs the worker's database writes itself, connected as the real unprivileged worker role.
 */
class KnowledgeDocumentIntegrationTest extends IntegrationTestBase {

    private static final byte[] PDF = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.US_ASCII);

    private static TwoTenants tenants;

    @Autowired
    private OutboxProcessor outbox;

    @Autowired
    private InMemoryStorage storage;

    @Autowired
    private RecordingIngestion ingestion;

    @BeforeEach
    void setUp() {
        if (tenants == null) {
            tenants = TwoTenants.create(this);
        }
        ingestion.failing = false;
        drainOutbox();
    }

    private static String documents(UUID workspaceId) {
        return "/api/v1/workspaces/" + workspaceId + "/knowledge/documents";
    }

    private static byte[] text(String label) {
        return (label + " " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
    }

    private void drainOutbox() {
        for (int i = 0; i < 200 && outbox.processNext(); i++) {
            // keep going
        }
    }

    private UUID uploadText(Session session, UUID workspaceId, String filename) {
        Response response = uploadFile(documents(workspaceId), filename, text(filename), session);
        assertThat(response.status()).as(response.body()).isEqualTo(202);
        return UUID.fromString(response.json("$.id"));
    }

    private String statusOf(Session session, UUID workspaceId, UUID documentId) {
        return get(documents(workspaceId) + "/" + documentId, session).json("$.status");
    }

    /** Does what the worker does for a successful run, as the worker's own database role. */
    private void indexAsWorker(UUID tenantId, UUID documentId, int chunks) throws SQLException {
        try (Connection connection = workerConnection()) {
            connection.setAutoCommit(false);
            setTenant(connection, tenantId);
            update(connection, "UPDATE documents SET status = 'PROCESSING', updated_at = now() WHERE tenant_id = ? AND id = ?", tenantId, documentId);
            update(connection, "DELETE FROM document_chunks WHERE tenant_id = ? AND document_id = ?", tenantId, documentId);
            for (int i = 0; i < chunks; i++) {
                update(
                        connection,
                        """
                        INSERT INTO document_chunks (id, tenant_id, document_id, chunk_index, content, embedding,
                                                     embedding_model, embedding_revision, embedding_dimension)
                        VALUES (?, ?, ?, ?, ?, '[0.1,0.2,0.3]'::vector, 'test-model', 'r1', 3)
                        """,
                        UUID.randomUUID(),
                        tenantId,
                        documentId,
                        i,
                        "chunk " + i);
            }
            update(
                    connection,
                    "UPDATE documents SET status = 'READY', chunk_count = ?, processed_at = now(), updated_at = now() WHERE tenant_id = ? AND id = ?",
                    chunks,
                    tenantId,
                    documentId);
            connection.commit();
        }
    }

    private static void setTenant(Connection connection, UUID tenantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
            statement.setString(1, tenantId.toString());
            statement.execute();
        }
    }

    private static int update(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            return statement.executeUpdate();
        }
    }

    /** Counts chunks as the owner, who sees every tenant, so tests can check what really exists. */
    private static int chunkCount(UUID documentId, boolean activeOnly) throws SQLException {
        try (Connection connection = ownerConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM document_chunks WHERE document_id = ?" + (activeOnly ? " AND active" : ""))) {
            statement.setObject(1, documentId);
            ResultSet result = statement.executeQuery();
            result.next();
            return result.getInt(1);
        }
    }

    @Test
    void uploadsOfEachSupportedFormatAreAcceptedAndStoredPrivately() {
        Session owner = tenants.ownerA();

        Response pdf = uploadFile(documents(tenants.techStore()), "Refund Policy.pdf", concat(PDF, text("pdf")), owner);
        Response txt = uploadFile(documents(tenants.techStore()), "delivery.txt", text("txt"), owner);
        Response md = uploadFile(documents(tenants.techStore()), "faq.md", text("# FAQ"), owner);

        assertThat(pdf.status()).as(pdf.body()).isEqualTo(202);
        assertThat(pdf.<String>json("$.status")).isEqualTo("UPLOADED");
        assertThat(pdf.<String>json("$.mime")).isEqualTo("application/pdf");
        assertThat(pdf.<String>json("$.filename")).isEqualTo("Refund Policy.pdf");
        assertThat(pdf.<Integer>json("$.version")).isEqualTo(1);
        assertThat(txt.<String>json("$.mime")).isEqualTo("text/plain");
        assertThat(md.<String>json("$.mime")).isEqualTo("text/markdown");

        // Storage keys are generated from identifiers; the filename never becomes part of a path,
        // and neither the key nor the hash is exposed through the API.
        String id = pdf.json("$.id");
        assertThat(storage.objects.keySet()).anyMatch(key -> key.equals("tenants/" + tenants.techStore() + "/documents/" + id));
        assertThat(storage.objects.keySet()).noneMatch(key -> key.contains("Refund") || key.contains(".pdf"));
        assertThat(pdf.body()).doesNotContain("storage_key").doesNotContain("content_hash").doesNotContain("tenants/");
    }

    @Test
    void invalidUploadsAreRejectedAndNothingIsStored() {
        Session owner = tenants.ownerA();
        int storedBefore = storage.objects.size();
        byte[] executable = {'M', 'Z', (byte) 0x90, 0x00, 0x03};

        assertRejected(uploadFile(documents(tenants.techStore()), "tool.exe", executable, owner), 415, "UNSUPPORTED_FILE_TYPE");
        assertRejected(uploadFile(documents(tenants.techStore()), "fake.pdf", executable, owner), 400, "CONTENT_MISMATCH");
        assertRejected(uploadFile(documents(tenants.techStore()), "fake.txt", executable, owner), 400, "CONTENT_MISMATCH");
        assertRejected(uploadFile(documents(tenants.techStore()), "empty.txt", new byte[0], owner), 400, "EMPTY_FILE");
        assertRejected(
                uploadFile(documents(tenants.techStore()), "huge.txt", new byte[(int) MAX_UPLOAD_BYTES + 1], owner),
                413,
                "FILE_TOO_LARGE");

        assertThat(storage.objects).hasSize(storedBefore);
    }

    @Test
    void identicalContentCannotBeUploadedTwiceWhileTheFirstIsLive() {
        byte[] content = text("duplicate");
        assertThat(uploadFile(documents(tenants.techStore()), "one.txt", content, tenants.ownerA()).status())
                .isEqualTo(202);

        Response second = uploadFile(documents(tenants.techStore()), "two.txt", content, tenants.ownerA());
        // The same bytes in another tenant are that tenant's own business.
        Response otherTenant = uploadFile(documents(tenants.hotelPro()), "one.txt", content, tenants.ownerB());

        assertRejected(second, 409, "DUPLICATE_DOCUMENT");
        assertThat(otherTenant.status()).isEqualTo(202);
    }

    @Test
    void theOutboxHandsAnUploadToTheWorkerExactlyOnce() {
        UUID documentId = uploadText(tenants.ownerA(), tenants.techStore(), "handoff.txt");
        assertThat(statusOf(tenants.ownerA(), tenants.techStore(), documentId)).isEqualTo("UPLOADED");

        drainOutbox();
        drainOutbox();

        assertThat(statusOf(tenants.ownerA(), tenants.techStore(), documentId)).isEqualTo("QUEUED");
        assertThat(ingestion.enqueued).containsOnlyOnce(tenants.techStore() + ":" + documentId);
    }

    @Test
    void aFailingWorkerIsRetriedAndTheDocumentFailsOnlyAfterTheRetryLimit() {
        ingestion.failing = true;
        UUID documentId = uploadText(tenants.ownerA(), tenants.techStore(), "unlucky.txt");

        outbox.processNext();
        assertThat(statusOf(tenants.ownerA(), tenants.techStore(), documentId)).isEqualTo("UPLOADED");

        // The worker comes back before the limit: the next attempt succeeds.
        ingestion.failing = false;
        drainOutbox();
        assertThat(statusOf(tenants.ownerA(), tenants.techStore(), documentId)).isEqualTo("QUEUED");

        ingestion.failing = true;
        UUID doomed = uploadText(tenants.ownerA(), tenants.techStore(), "doomed.txt");
        drainOutbox();

        Response failed = get(documents(tenants.techStore()) + "/" + doomed, tenants.ownerA());
        assertThat(failed.<String>json("$.status")).isEqualTo("FAILED");
        assertThat(failed.<String>json("$.error_code")).isEqualTo("DISPATCH_FAILED");
        assertThat(ingestion.enqueued).doesNotContain(tenants.techStore() + ":" + doomed);
    }

    @Test
    void anIndexedDocumentCanBeArchivedReprocessedAndDeleted() throws SQLException {
        Session owner = tenants.ownerA();
        UUID documentId = uploadText(owner, tenants.techStore(), "lifecycle.txt");
        String base = documents(tenants.techStore()) + "/" + documentId;

        // Lifecycle actions wait until processing has finished.
        assertRejected(post(base + "/archive", null, owner), 409, "DOCUMENT_BUSY");
        drainOutbox();
        indexAsWorker(tenants.techStore(), documentId, 3);

        Response ready = get(base, owner);
        assertThat(ready.<String>json("$.status")).isEqualTo("READY");
        assertThat(ready.<Integer>json("$.chunk_count")).isEqualTo(3);
        assertThat(chunkCount(documentId, true)).isEqualTo(3);

        // FR-019: an archived document keeps its chunks but none is eligible for retrieval.
        assertThat(post(base + "/archive", null, owner).<String>json("$.status")).isEqualTo("ARCHIVED");
        assertThat(chunkCount(documentId, true)).isZero();
        assertThat(chunkCount(documentId, false)).isEqualTo(3);

        Response reprocess = post(base + "/reprocess", null, owner);
        assertThat(reprocess.status()).isEqualTo(202);
        assertThat(reprocess.<String>json("$.status")).isEqualTo("UPLOADED");
        drainOutbox();
        assertThat(statusOf(owner, tenants.techStore(), documentId)).isEqualTo("QUEUED");
        indexAsWorker(tenants.techStore(), documentId, 2);
        assertThat(chunkCount(documentId, true)).isEqualTo(2);

        // Deleting removes the chunks at once and the stored file through the outbox.
        String storageKey = "tenants/" + tenants.techStore() + "/documents/" + documentId;
        assertThat(storage.objects).containsKey(storageKey);
        assertThat(delete(base, owner).status()).isEqualTo(204);
        assertThat(chunkCount(documentId, false)).isZero();
        assertThat(get(base, owner).status()).isEqualTo(404);
        assertThat(get(documents(tenants.techStore()), owner).body()).doesNotContain(documentId.toString());
        drainOutbox();
        assertThat(storage.objects).doesNotContainKey(storageKey);
    }

    @Test
    void replacingADocumentCreatesANewVersionInTheSameLineage() {
        Session owner = tenants.ownerA();
        UUID first = uploadText(owner, tenants.techStore(), "returns-v1.txt");

        Response replaced = uploadFile(
                documents(tenants.techStore()) + "/" + first + "/replace", "returns-v2.txt", text("v2"), owner);

        assertThat(replaced.status()).as(replaced.body()).isEqualTo(202);
        assertThat(replaced.<Integer>json("$.version")).isEqualTo(2);
        assertThat(replaced.<String>json("$.lineage_id")).isEqualTo(first.toString());
        assertThat(replaced.<String>json("$.id")).isNotEqualTo(first.toString());

        // Only the newest version can be replaced or reprocessed.
        Response staleReplace = uploadFile(
                documents(tenants.techStore()) + "/" + first + "/replace", "returns-v3.txt", text("v3"), owner);
        assertRejected(staleReplace, 409, "NOT_LATEST_VERSION");
    }

    @Test
    void onlyOwnersAndAdminsCanManageKnowledge() {
        Session agent = tenants.agentA().session();
        UUID documentId = uploadText(tenants.ownerA(), tenants.techStore(), "restricted.txt");
        String base = documents(tenants.techStore());

        assertThat(get(base, agent).status()).isEqualTo(403);
        assertThat(uploadFile(base, "agent.txt", text("agent"), agent).status()).isEqualTo(403);
        assertThat(get(base + "/" + documentId, agent).status()).isEqualTo(403);
        assertThat(post(base + "/" + documentId + "/archive", null, agent).status()).isEqualTo(403);
        assertThat(delete(base + "/" + documentId, agent).status()).isEqualTo(403);
    }

    @Test
    void documentsOfAnotherTenantCannotBeSeenOrChanged() {
        UUID documentB = uploadText(tenants.ownerB(), tenants.hotelPro(), "check-in-policy.txt");
        Session ownerA = tenants.ownerA();
        String foreign = documents(tenants.hotelPro());
        // Object-ID substitution: the caller's own workspace with another tenant's document ID.
        String substituted = documents(tenants.techStore()) + "/" + documentB;

        assertThat(get(foreign, ownerA).status()).isEqualTo(404);
        assertThat(uploadFile(foreign, "intruder.txt", text("x"), ownerA).status()).isEqualTo(404);
        for (String base : List.of(foreign + "/" + documentB, substituted)) {
            assertThat(get(base, ownerA).status()).as(base).isEqualTo(404);
            assertThat(post(base + "/archive", null, ownerA).status()).isEqualTo(404);
            assertThat(post(base + "/reprocess", null, ownerA).status()).isEqualTo(404);
            assertThat(delete(base, ownerA).status()).isEqualTo(404);
            assertThat(uploadFile(base + "/replace", "evil.txt", text("evil"), ownerA).status())
                    .isEqualTo(404);
        }

        assertThat(get(documents(tenants.techStore()), ownerA).body())
                .doesNotContain(documentB.toString())
                .doesNotContain("check-in-policy");
        assertThat(statusOf(tenants.ownerB(), tenants.hotelPro(), documentB)).isEqualTo("UPLOADED");
    }

    @Test
    void theWorkerRoleIsConfinedToOneTenantsKnowledgeAndNothingElse() throws SQLException {
        UUID documentA = uploadText(tenants.ownerA(), tenants.techStore(), "worker-a.txt");
        UUID documentB = uploadText(tenants.ownerB(), tenants.hotelPro(), "worker-b.txt");
        indexAsWorker(tenants.techStore(), documentA, 2);
        indexAsWorker(tenants.hotelPro(), documentB, 2);

        try (Connection worker = workerConnection();
                Statement statement = worker.createStatement()) {
            // No privileges beyond knowledge processing.
            for (String table : List.of("users", "memberships", "tenants", "invitations", "audit_events", "outbox_events", "widgets", "ai_assistants", "refresh_tokens")) {
                assertThatThrownBy(() -> statement.executeQuery("SELECT count(*) FROM " + table))
                        .as(table)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("permission denied");
            }
            ResultSet role = statement.executeQuery(
                    "SELECT rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb FROM pg_roles WHERE rolname = current_user");
            role.next();
            assertThat(role.getBoolean(1)).isFalse();

            // Without a tenant context it sees nothing.
            assertThat(count(worker, "documents")).isZero();
            assertThat(count(worker, "document_chunks")).isZero();

            worker.setAutoCommit(false);
            setTenant(worker, tenants.techStore());
            assertThat(count(worker, "documents WHERE id = '" + documentA + "'")).isEqualTo(1);
            assertThat(count(worker, "documents WHERE id = '" + documentB + "'")).isZero();
            assertThat(count(worker, "document_chunks WHERE document_id = '" + documentB + "'")).isZero();
            // Other tenants' rows cannot be changed, and rows cannot be written into another tenant.
            assertThat(update(worker, "UPDATE documents SET status = 'FAILED' WHERE id = ?", documentB)).isZero();
            assertThat(update(worker, "DELETE FROM document_chunks WHERE document_id = ?", documentB)).isZero();
            assertThatThrownBy(() -> update(
                            worker,
                            """
                            INSERT INTO document_chunks (id, tenant_id, document_id, chunk_index, content, embedding,
                                                         embedding_model, embedding_revision, embedding_dimension)
                            VALUES (?, ?, ?, 99, 'forged', '[1,2,3]'::vector, 'm', 'r', 3)
                            """,
                            UUID.randomUUID(),
                            tenants.hotelPro(),
                            documentB))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security");
            worker.rollback();

            // Only the processing columns of a document are writable.
            setTenant(worker, tenants.techStore());
            assertThatThrownBy(() -> update(worker, "UPDATE documents SET storage_key = 'elsewhere' WHERE id = ?", documentA))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
            worker.rollback();
            setTenant(worker, tenants.techStore());
            assertThatThrownBy(() -> update(worker, "DELETE FROM documents WHERE id = ?", documentA))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
            worker.rollback();
        }
        assertThat(chunkCount(documentB, true)).isEqualTo(2);
    }

    @Test
    void aChunkCannotReferenceADocumentOfAnotherTenantOrMisstateItsDimension() throws SQLException {
        UUID documentB = uploadText(tenants.ownerB(), tenants.hotelPro(), "fk-target.txt");
        try (Connection worker = workerConnection()) {
            worker.setAutoCommit(false);
            setTenant(worker, tenants.techStore());
            // Tenant A's context and tenant_id, but tenant B's document: the composite key refuses it.
            assertThatThrownBy(() -> update(
                            worker,
                            """
                            INSERT INTO document_chunks (id, tenant_id, document_id, chunk_index, content, embedding,
                                                         embedding_model, embedding_revision, embedding_dimension)
                            VALUES (?, ?, ?, 0, 'cross', '[1,2,3]'::vector, 'm', 'r', 3)
                            """,
                            UUID.randomUUID(),
                            tenants.techStore(),
                            documentB))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("fk_document_chunks_document_same_tenant");
            worker.rollback();
        }
        UUID documentA = uploadText(tenants.ownerA(), tenants.techStore(), "dimension.txt");
        try (Connection worker = workerConnection()) {
            worker.setAutoCommit(false);
            setTenant(worker, tenants.techStore());
            assertThatThrownBy(() -> update(
                            worker,
                            """
                            INSERT INTO document_chunks (id, tenant_id, document_id, chunk_index, content, embedding,
                                                         embedding_model, embedding_revision, embedding_dimension)
                            VALUES (?, ?, ?, 0, 'wrong', '[1,2,3]'::vector, 'm', 'r', 384)
                            """,
                            UUID.randomUUID(),
                            tenants.techStore(),
                            documentA))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_document_chunks_dimension");
            worker.rollback();
        }
    }

    @Test
    void pendingOutboxEventsAreVisibleOnlyToTheDispatcherOrTheirOwnTenant() throws SQLException {
        uploadText(tenants.ownerA(), tenants.techStore(), "outbox-a.txt");
        uploadText(tenants.ownerB(), tenants.hotelPro(), "outbox-b.txt");

        try (Connection app = appConnection()) {
            assertThat(count(app, "outbox_events")).isZero();

            app.setAutoCommit(false);
            setTenant(app, tenants.techStore());
            try (Statement statement = app.createStatement()) {
                ResultSet result = statement.executeQuery("SELECT DISTINCT tenant_id FROM outbox_events");
                assertThat(result.next()).isTrue();
                assertThat(result.getObject(1, UUID.class)).isEqualTo(tenants.techStore());
                assertThat(result.next()).isFalse();
            }
            app.rollback();

            // The dispatcher setting unlocks reading across tenants, and nothing else.
            try (Statement statement = app.createStatement()) {
                statement.execute("SELECT set_config('app.outbox_dispatcher', 'on', true)");
            }
            assertThat(count(app, "outbox_events WHERE status = 'PENDING'")).isGreaterThanOrEqualTo(2);
            assertThat(update(app, "UPDATE outbox_events SET status = 'DELIVERED'")).isZero();
            assertThat(count(app, "documents")).isZero();
            assertThat(count(app, "memberships")).isZero();
            app.rollback();
        }
    }

    @Test
    void documentChangesAreAudited() {
        Session owner = newUser("doc-audit");
        UUID workspaceId = createWorkspace(owner, "DocAudit");
        UUID documentId = uploadText(owner, workspaceId, "audited.txt");
        delete(documents(workspaceId) + "/" + documentId, owner);

        Response audit = get("/api/v1/workspaces/" + workspaceId + "/audit-events", owner);

        assertThat(audit.<List<String>>json("$.items[*].action")).contains("DOCUMENT_UPLOADED", "DOCUMENT_DELETED");
        // File names can be sensitive, so they are not copied into the audit log.
        assertThat(audit.body()).doesNotContain("audited.txt");
    }

    private static int count(Connection connection, String from) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            ResultSet result = statement.executeQuery("SELECT count(*) FROM " + from);
            result.next();
            return result.getInt(1);
        }
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = new byte[first.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    private static void assertRejected(Response response, int status, String code) {
        assertThat(response.status()).as(response.body()).isEqualTo(status);
        assertThat(response.errorCode()).isEqualTo(code);
    }
}
