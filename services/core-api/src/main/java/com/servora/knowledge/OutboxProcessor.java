package com.servora.knowledge;

import com.servora.common.tenant.TenantContext;
import com.servora.knowledge.KnowledgeConfig.OutboxProperties;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivers one outbox event per transaction.
 *
 * <p>Delivery is at-least-once: if the process dies after the worker accepted a job but before this
 * transaction commits, the event is delivered again. Receivers are therefore idempotent.
 */
@Component
public class OutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboxProcessor.class);
    private static final int MAX_BACKOFF_SHIFT = 6;

    private final JdbcClient jdbc;
    private final TenantContext tenantContext;
    private final IngestionClient ingestion;
    private final DocumentStorage storage;
    private final OutboxProperties properties;

    public OutboxProcessor(
            JdbcClient jdbc,
            TenantContext tenantContext,
            IngestionClient ingestion,
            DocumentStorage storage,
            OutboxProperties properties) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.ingestion = ingestion;
        this.storage = storage;
        this.properties = properties;
    }

    /** Processes the oldest due event. Returns false when nothing is due. */
    @Transactional
    public boolean processNext() {
        // The dispatcher has no tenant of its own. This setting lets it find pending events across
        // tenants; it unlocks reading only.
        tenantContext.enableOutboxDispatch();
        Optional<Map<String, Object>> candidate = jdbc.sql("""
                        SELECT id, tenant_id FROM outbox_events
                        WHERE status = 'PENDING' AND next_attempt_at <= now()
                        ORDER BY next_attempt_at, created_at
                        LIMIT 1
                        """)
                .query()
                .listOfRows()
                .stream()
                .findFirst();
        if (candidate.isEmpty()) {
            return false;
        }
        UUID eventId = (UUID) candidate.get().get("id");
        UUID tenantId = (UUID) candidate.get().get("tenant_id");

        // The tenant comes from the server's own outbox row. From here on, every statement is
        // scoped to it, and the row lock stops another instance delivering the same event.
        tenantContext.bindTenant(tenantId);
        Optional<Map<String, Object>> locked = jdbc.sql("""
                        SELECT event_type, aggregate_id, attempts FROM outbox_events
                        WHERE tenant_id = :tenantId AND id = :id AND status = 'PENDING'
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("tenantId", tenantId)
                .param("id", eventId)
                .query()
                .listOfRows()
                .stream()
                .findFirst();
        if (locked.isEmpty()) {
            return true;
        }
        String eventType = (String) locked.get().get("event_type");
        UUID documentId = (UUID) locked.get().get("aggregate_id");
        int attempts = (Integer) locked.get().get("attempts") + 1;

        try {
            deliver(eventType, tenantId, documentId);
            jdbc.sql("""
                            UPDATE outbox_events
                            SET status = 'DELIVERED', attempts = :attempts, delivered_at = now(), last_error = NULL
                            WHERE tenant_id = :tenantId AND id = :id
                            """)
                    .param("attempts", attempts)
                    .param("tenantId", tenantId)
                    .param("id", eventId)
                    .update();
        } catch (RuntimeException e) {
            // Only the exception type is stored and logged: messages can contain URLs or content.
            String reason = e.getClass().getSimpleName();
            boolean exhausted = attempts >= properties.maxAttempts();
            log.warn("Outbox delivery failed: type={} attempt={} exhausted={} reason={}", eventType, attempts, exhausted, reason);
            long delaySeconds =
                    properties.retryBaseDelay().toSeconds() * (1L << Math.min(attempts - 1, MAX_BACKOFF_SHIFT));
            jdbc.sql("""
                            UPDATE outbox_events
                            SET status = :status, attempts = :attempts, last_error = :reason,
                                next_attempt_at = now() + make_interval(secs => :delay)
                            WHERE tenant_id = :tenantId AND id = :id
                            """)
                    .param("status", exhausted ? "FAILED" : "PENDING")
                    .param("attempts", attempts)
                    .param("reason", reason)
                    .param("delay", (double) delaySeconds)
                    .param("tenantId", tenantId)
                    .param("id", eventId)
                    .update();
            if (exhausted && eventType.equals("DOCUMENT_INGEST")) {
                jdbc.sql("""
                                UPDATE documents SET status = 'FAILED', error_code = 'DISPATCH_FAILED', updated_at = now()
                                WHERE tenant_id = :tenantId AND id = :id AND status = 'UPLOADED'
                                """)
                        .param("tenantId", tenantId)
                        .param("id", documentId)
                        .update();
            }
        }
        return true;
    }

    private void deliver(String eventType, UUID tenantId, UUID documentId) {
        switch (eventType) {
            case "DOCUMENT_INGEST" -> {
                boolean stillWanted = jdbc.sql("""
                                SELECT count(*) FROM documents
                                WHERE tenant_id = :tenantId AND id = :id AND status = 'UPLOADED'
                                """)
                                .param("tenantId", tenantId)
                                .param("id", documentId)
                                .query(Long.class)
                                .single()
                        > 0;
                if (!stillWanted) {
                    return; // Deleted or archived before it was handed over: nothing to process.
                }
                ingestion.enqueue(tenantId, documentId);
                // The worker may already have picked the job up; only move it forward from UPLOADED.
                jdbc.sql("""
                                UPDATE documents SET status = 'QUEUED', updated_at = now()
                                WHERE tenant_id = :tenantId AND id = :id AND status = 'UPLOADED'
                                """)
                        .param("tenantId", tenantId)
                        .param("id", documentId)
                        .update();
            }
            case "DOCUMENT_STORAGE_DELETE" -> {
                Optional<String> storageKey = jdbc.sql("""
                                SELECT storage_key FROM documents
                                WHERE tenant_id = :tenantId AND id = :id AND status = 'DELETED'
                                  AND storage_key IS NOT NULL
                                """)
                        .param("tenantId", tenantId)
                        .param("id", documentId)
                        .query(String.class)
                        .optional();
                if (storageKey.isPresent()) {
                    storage.delete(storageKey.get());
                    jdbc.sql("UPDATE documents SET storage_key = NULL, updated_at = now() WHERE tenant_id = :tenantId AND id = :id")
                            .param("tenantId", tenantId)
                            .param("id", documentId)
                            .update();
                }
            }
            default -> throw new IllegalStateException("Unknown outbox event type");
        }
    }
}
