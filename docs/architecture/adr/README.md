# Architecture decision records

Each record states the context, the decision and its consequences. Supersede a record with a new one rather than rewriting history.

| ADR | Decision | Status |
|---|---|---|
| [ADR-001](0001-modular-monolith-core-with-a-separate-python-ai-service.md) | Modular monolith core with a separate Python AI service | Accepted |
| [ADR-002](0002-shared-schema-multi-tenancy-with-tenant-id-rls-and-tenant-aw.md) | Shared schema multi-tenancy with tenant_id, RLS and tenant-aware foreign keys | Accepted |
| [ADR-003](0003-postgresql-with-pgvector-as-the-single-database.md) | PostgreSQL with pgvector as the single database | Accepted |
| [ADR-004](0004-private-object-storage-with-backend-controlled-access.md) | Private object storage with backend-controlled access | Accepted |
| [ADR-005](0005-iframe-widget-mounted-by-a-small-loader-script.md) | Iframe widget mounted by a small loader script | Accepted |
| [ADR-006](0006-asynchronous-worker-for-document-ingestion.md) | Asynchronous worker for document ingestion | Accepted |
| [ADR-007](0007-retrieval-augmented-generation-only-no-autonomous-consequent.md) | Retrieval-augmented generation only, no autonomous consequential actions | Accepted |
| [ADR-008](0008-flyway-in-the-core-api-owns-the-entire-database-schema.md) | Flyway in the core API owns the entire database schema | Accepted (recommended in M0) |
| [ADR-009](0009-ai-service-and-worker-access-postgresql-directly-with-restri.md) | AI service and worker access PostgreSQL directly with restricted roles under RLS | Accepted (recommended in M0) |
| [ADR-010](0010-outbox-plus-an-internal-enqueue-endpoint-for-java-to-celery.md) | Outbox plus an internal enqueue endpoint for Java-to-Celery dispatch | Accepted (recommended in M0) |
| [ADR-011](0011-in-memory-access-token-with-a-rotating-refresh-cookie-argon2.md) | In-memory access token with a rotating refresh cookie; Argon2id password hashing | Accepted (recommended in M0) |
| [ADR-012](0012-local-sentence-transformers-model-for-embeddings.md) | Local Sentence Transformers model for embeddings | Accepted (recommended in M0) |
| [ADR-013](0013-llm-provider-behind-an-adapter.md) | LLM provider behind an adapter | Accepted (Anthropic Claude, decided 2026-11-17) |
| [ADR-014](0014-server-sent-events-from-the-core-api-for-customer-streaming.md) | Server-Sent Events from the core API for customer streaming | Accepted (amended in M4: final message only, no partial text) |
| [ADR-015](0015-row-level-security-context-beyond-the-tenant-id.md) | Row-Level Security context beyond the tenant ID | Accepted (decided in M1) |
| [ADR-016](0016-outbox-dispatcher-reads-across-tenants.md) | The outbox dispatcher reads pending events across tenants | Accepted (decided in M2) |
| [ADR-017](0017-public-widget-access-and-session-tokens.md) | Public widget access, origins and session tokens | Accepted (decided in M4) |
| [ADR-018](0018-human-handoff-escalation-and-takeover.md) | Human handoff: escalation, takeover and agent replies | Accepted (decided in M5) |
| [ADR-019](0019-plans-usage-metering-and-platform-administration.md) | Plans, usage metering and platform administration | Accepted (decided in M6) |
| [ADR-020](0020-retention-deletion-and-export.md) | Conversation retention, deletion and workspace export | Accepted (decided in M6) |
