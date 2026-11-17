package com.servora.knowledge;

import com.servora.audit.AuditService;
import com.servora.common.error.ApiException;
import com.servora.common.web.PageResponse;
import com.servora.knowledge.KnowledgeConfig.KnowledgeProperties;
import com.servora.knowledge.UploadValidator.DetectedFile;
import com.servora.membership.Permission;
import com.servora.membership.WorkspaceAccess;
import com.servora.membership.WorkspaceContext;
import com.servora.usage.UsageService;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Knowledge documents: upload, versions and lifecycle. Processing itself happens in the document worker. */
@Service
public class DocumentService {

    public enum Status {
        /** Stored privately; the processing job has not been handed to the worker yet. */
        UPLOADED,
        QUEUED,
        PROCESSING,
        /** Indexed and eligible for retrieval. */
        READY,
        FAILED,
        /** Kept, but its chunks are inactive and cannot be retrieved. */
        ARCHIVED,
        /** Chunks removed and the stored file scheduled for removal. Only metadata remains. */
        DELETED
    }

    public record DocumentView(
            UUID id,
            UUID lineageId,
            int version,
            String filename,
            String mime,
            long sizeBytes,
            Status status,
            String errorCode,
            int chunkCount,
            Instant createdAt,
            Instant processedAt) {}

    private static final Set<Status> IN_FLIGHT = Set.of(Status.UPLOADED, Status.QUEUED, Status.PROCESSING);
    private static final String COLUMNS = """
            id, lineage_id, version, original_filename, detected_mime, size_bytes, status, error_code,
            chunk_count, created_at, processed_at
            """;

    private final JdbcClient jdbc;
    private final WorkspaceAccess access;
    private final DocumentStorage storage;
    private final UploadRateLimiter rateLimiter;
    private final AuditService audit;
    private final KnowledgeProperties properties;
    private final UsageService usage;

    public DocumentService(
            JdbcClient jdbc,
            WorkspaceAccess access,
            DocumentStorage storage,
            UploadRateLimiter rateLimiter,
            AuditService audit,
            KnowledgeProperties properties,
            UsageService usage) {
        this.jdbc = jdbc;
        this.access = access;
        this.storage = storage;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.properties = properties;
        this.usage = usage;
    }

    /** Called when a workspace is created, inside that transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createDefaultSource(WorkspaceContext ctx) {
        jdbc.sql("""
                        INSERT INTO knowledge_sources (id, tenant_id, name, source_type, status)
                        VALUES (:id, :tenantId, 'Uploaded documents', 'UPLOAD', 'ACTIVE')
                        """)
                .param("id", UUID.randomUUID())
                .param("tenantId", ctx.tenantId())
                .update();
    }

    @Transactional
    public DocumentView upload(UUID userId, UUID workspaceId, String filename, byte[] content) {
        WorkspaceContext ctx = access.authorize(userId, workspaceId, Permission.MANAGE_KNOWLEDGE);
        UUID documentId = UUID.randomUUID();
        return store(ctx, documentId, documentId, 1, filename, content);
    }

    /**
     * Uploads a new version of an existing document. The current version keeps answering until the
     * new one is indexed; the worker then retires the older versions in the same transaction.
     */
    @Transactional
    public DocumentView replace(UUID userId, UUID workspaceId, UUID documentId, String filename, byte[] content) {
        WorkspaceContext ctx = access.authorize(userId, workspaceId, Permission.MANAGE_KNOWLEDGE);
        DocumentView current = requireLatest(ctx, documentId);
        return store(ctx, UUID.randomUUID(), current.lineageId(), current.version() + 1, filename, content);
    }

    @Transactional(readOnly = true)
    public PageResponse<DocumentView> list(UUID userId, UUID workspaceId, int page, int size) {
        WorkspaceContext ctx = access.authorize(userId, workspaceId, Permission.MANAGE_KNOWLEDGE);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), PageResponse.MAX_SIZE);
        List<DocumentView> items = jdbc.sql("SELECT " + COLUMNS + """
                        FROM documents
                        WHERE tenant_id = :tenantId AND status <> 'DELETED'
                        ORDER BY created_at DESC, id
                        LIMIT :limit OFFSET :offset
                        """)
                .param("tenantId", ctx.tenantId())
                .param("limit", safeSize)
                .param("offset", (long) safePage * safeSize)
                .query(DocumentService::map)
                .list();
        long total = jdbc.sql("SELECT count(*) FROM documents WHERE tenant_id = :tenantId AND status <> 'DELETED'")
                .param("tenantId", ctx.tenantId())
                .query(Long.class)
                .single();
        return new PageResponse<>(items, safePage, safeSize, total);
    }

    @Transactional(readOnly = true)
    public DocumentView get(UUID userId, UUID workspaceId, UUID documentId) {
        WorkspaceContext ctx = access.authorize(userId, workspaceId, Permission.MANAGE_KNOWLEDGE);
        return require(ctx, documentId);
    }

    /** Takes a document out of retrieval without removing it. */
    @Transactional
    public DocumentView archive(UUID userId, UUID workspaceId, UUID documentId) {
        WorkspaceContext ctx = access.authorize(userId, workspaceId, Permission.MANAGE_KNOWLEDGE);
        DocumentView document = lock(ctx, documentId);
        if (IN_FLIGHT.contains(document.status())) {
            throw ApiException.conflict("DOCUMENT_BUSY", "The document is still being processed. Try again shortly.");
        }
        deactivateChunks(ctx, documentId);
        setStatus(ctx, documentId, Status.ARCHIVED);
        audit.record(ctx, "DOCUMENT_ARCHIVED", "document", documentId, Map.of());
        return require(ctx, documentId);
    }

    /** Sends a document through processing again, for example after a failure or to bring back an archived one. */
    @Transactional
    public DocumentView reprocess(UUID userId, UUID workspaceId, UUID documentId) {
        WorkspaceContext ctx = access.authorize(userId, workspaceId, Permission.MANAGE_KNOWLEDGE);
        DocumentView document = lock(ctx, documentId);
        if (IN_FLIGHT.contains(document.status())) {
            throw ApiException.conflict("DOCUMENT_BUSY", "The document is already being processed.");
        }
        requireLatest(ctx, documentId);
        jdbc.sql("""
                        UPDATE documents SET status = 'UPLOADED', error_code = NULL, updated_at = now()
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", ctx.tenantId())
                .param("id", documentId)
                .update();
        enqueue(ctx, "DOCUMENT_INGEST", documentId, "{}");
        audit.record(ctx, "DOCUMENT_REPROCESS_REQUESTED", "document", documentId, Map.of());
        return require(ctx, documentId);
    }

    /**
     * Deletes a document. Its chunks are removed in this transaction, so it stops being retrievable
     * the moment this commits. The stored file is removed afterwards through the outbox, because
     * object storage cannot take part in the database transaction.
     */
    @Transactional
    public void delete(UUID userId, UUID workspaceId, UUID documentId) {
        WorkspaceContext ctx = access.authorize(userId, workspaceId, Permission.MANAGE_KNOWLEDGE);
        lock(ctx, documentId);
        jdbc.sql("DELETE FROM document_chunks WHERE tenant_id = :tenantId AND document_id = :id")
                .param("tenantId", ctx.tenantId())
                .param("id", documentId)
                .update();
        jdbc.sql("""
                        UPDATE documents SET status = 'DELETED', chunk_count = 0, error_code = NULL, updated_at = now()
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", ctx.tenantId())
                .param("id", documentId)
                .update();
        enqueue(ctx, "DOCUMENT_STORAGE_DELETE", documentId, "{}");
        audit.record(ctx, "DOCUMENT_DELETED", "document", documentId, Map.of());
    }

    private DocumentView store(
            WorkspaceContext ctx, UUID documentId, UUID lineageId, int version, String filename, byte[] content) {
        if (!rateLimiter.tryAcquire(ctx.tenantId())) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS, "UPLOAD_RATE_LIMITED", "Too many uploads. Try again later.");
        }
        DetectedFile file = UploadValidator.validate(filename, content, properties.maxUploadBytes());
        String contentHash = sha256(content);

        // Serialise uploads within a workspace so the duplicate and limit checks below cannot race.
        UUID sourceId = jdbc.sql("""
                        SELECT id FROM knowledge_sources
                        WHERE tenant_id = :tenantId AND source_type = 'UPLOAD' AND status = 'ACTIVE'
                        ORDER BY created_at LIMIT 1
                        FOR UPDATE
                        """)
                .param("tenantId", ctx.tenantId())
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> ApiException.conflict("NO_KNOWLEDGE_SOURCE", "This workspace has no knowledge source."));

        boolean duplicate = jdbc.sql("""
                        SELECT count(*) FROM documents
                        WHERE tenant_id = :tenantId AND content_hash = :hash
                          AND status IN ('UPLOADED', 'QUEUED', 'PROCESSING', 'READY')
                        """)
                        .param("tenantId", ctx.tenantId())
                        .param("hash", contentHash)
                        .query(Long.class)
                        .single()
                > 0;
        if (duplicate) {
            throw ApiException.conflict("DUPLICATE_DOCUMENT", "A document with exactly this content already exists.");
        }
        long existing = jdbc.sql("SELECT count(*) FROM documents WHERE tenant_id = :tenantId AND status <> 'DELETED'")
                .param("tenantId", ctx.tenantId())
                .query(Long.class)
                .single();
        if (existing >= properties.maxDocumentsPerWorkspace()) {
            throw ApiException.conflict(
                    "DOCUMENT_LIMIT_REACHED", "This workspace has reached its document limit. Delete a document first.");
        }
        usage.requireDocumentSlot(ctx.tenantId(), existing);

        // Built only from server-generated identifiers. The user's filename is never part of a path.
        String storageKey = "tenants/%s/documents/%s".formatted(ctx.tenantId(), documentId);
        storage.put(storageKey, content, file.mime());
        try {
            jdbc.sql("""
                            INSERT INTO documents (id, tenant_id, source_id, lineage_id, version, original_filename,
                                                   storage_key, detected_mime, size_bytes, content_hash, status,
                                                   uploaded_by_user_id)
                            VALUES (:id, :tenantId, :sourceId, :lineageId, :version, :filename, :storageKey, :mime,
                                    :size, :hash, 'UPLOADED', :userId)
                            """)
                    .param("id", documentId)
                    .param("tenantId", ctx.tenantId())
                    .param("sourceId", sourceId)
                    .param("lineageId", lineageId)
                    .param("version", version)
                    .param("filename", file.filename())
                    .param("storageKey", storageKey)
                    .param("mime", file.mime())
                    .param("size", (long) content.length)
                    .param("hash", contentHash)
                    .param("userId", ctx.userId())
                    .update();
            enqueue(ctx, "DOCUMENT_INGEST", documentId, "{}");
            usage.recordDocumentUploaded(ctx.tenantId(), documentId);
            audit.record(
                    ctx,
                    "DOCUMENT_UPLOADED",
                    "document",
                    documentId,
                    Map.of("version", String.valueOf(version), "mime", file.mime()));
            return require(ctx, documentId);
        } catch (RuntimeException e) {
            // The transaction will roll back; do not leave the stored object orphaned.
            try {
                storage.delete(storageKey);
            } catch (RuntimeException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
    }

    private void enqueue(WorkspaceContext ctx, String eventType, UUID documentId, String payload) {
        jdbc.sql("""
                        INSERT INTO outbox_events (id, tenant_id, event_type, aggregate_id, payload)
                        VALUES (:id, :tenantId, :eventType, :aggregateId, CAST(:payload AS jsonb))
                        """)
                .param("id", UUID.randomUUID())
                .param("tenantId", ctx.tenantId())
                .param("eventType", eventType)
                .param("aggregateId", documentId)
                .param("payload", payload)
                .update();
    }

    private void deactivateChunks(WorkspaceContext ctx, UUID documentId) {
        jdbc.sql("UPDATE document_chunks SET active = false WHERE tenant_id = :tenantId AND document_id = :id")
                .param("tenantId", ctx.tenantId())
                .param("id", documentId)
                .update();
    }

    private void setStatus(WorkspaceContext ctx, UUID documentId, Status status) {
        jdbc.sql("UPDATE documents SET status = :status, updated_at = now() WHERE tenant_id = :tenantId AND id = :id")
                .param("status", status.name())
                .param("tenantId", ctx.tenantId())
                .param("id", documentId)
                .update();
    }

    private DocumentView require(WorkspaceContext ctx, UUID documentId) {
        return find(ctx, documentId, "");
    }

    /** Loads the document and holds a row lock so a concurrent lifecycle change waits for this one. */
    private DocumentView lock(WorkspaceContext ctx, UUID documentId) {
        return find(ctx, documentId, " FOR UPDATE");
    }

    private DocumentView find(WorkspaceContext ctx, UUID documentId, String suffix) {
        return jdbc.sql("SELECT " + COLUMNS + """
                        FROM documents
                        WHERE tenant_id = :tenantId AND id = :id AND status <> 'DELETED'
                        """ + suffix)
                .param("tenantId", ctx.tenantId())
                .param("id", documentId)
                .query(DocumentService::map)
                .optional()
                .orElseThrow(() -> ApiException.notFound("DOCUMENT_NOT_FOUND", "The document was not found."));
    }

    private DocumentView requireLatest(WorkspaceContext ctx, UUID documentId) {
        DocumentView document = require(ctx, documentId);
        int latest = jdbc.sql("""
                        SELECT max(version) FROM documents
                        WHERE tenant_id = :tenantId AND lineage_id = :lineageId AND status <> 'DELETED'
                        """)
                .param("tenantId", ctx.tenantId())
                .param("lineageId", document.lineageId())
                .query(Integer.class)
                .single();
        if (document.version() != latest) {
            throw ApiException.conflict("NOT_LATEST_VERSION", "A newer version of this document exists.");
        }
        return document;
    }

    private static DocumentView map(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime processedAt = rs.getObject("processed_at", OffsetDateTime.class);
        return new DocumentView(
                rs.getObject("id", UUID.class),
                rs.getObject("lineage_id", UUID.class),
                rs.getInt("version"),
                rs.getString("original_filename"),
                rs.getString("detected_mime"),
                rs.getLong("size_bytes"),
                Status.valueOf(rs.getString("status")),
                rs.getString("error_code"),
                rs.getInt("chunk_count"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                processedAt == null ? null : processedAt.toInstant());
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
