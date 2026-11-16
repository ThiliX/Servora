-- M2 (part 2): knowledge sources, documents, chunks with embeddings, and the outbox.
--
-- Two runtime roles use these tables:
--   ${app_role}     the core API
--   ${worker_role}  the document worker, which may only read documents, update their processing
--                   columns, and write chunks
-- Both are unprivileged and subject to every policy below.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE knowledge_sources (
    id          uuid PRIMARY KEY,
    tenant_id   uuid        NOT NULL REFERENCES tenants (id),
    name        text        NOT NULL,
    source_type text        NOT NULL,
    status      text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_knowledge_sources_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_knowledge_sources_name UNIQUE (tenant_id, name),
    CONSTRAINT ck_knowledge_sources_type CHECK (source_type IN ('UPLOAD')),
    CONSTRAINT ck_knowledge_sources_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

-- One row per document version. Versions of the same logical document share a lineage_id.
CREATE TABLE documents (
    id                  uuid PRIMARY KEY,
    tenant_id           uuid        NOT NULL REFERENCES tenants (id),
    source_id           uuid        NOT NULL,
    lineage_id          uuid        NOT NULL,
    version             integer     NOT NULL,
    original_filename   text        NOT NULL,
    -- Generated object key in private storage; NULL once the stored object has been removed.
    storage_key         text,
    detected_mime       text        NOT NULL,
    size_bytes          bigint      NOT NULL,
    content_hash        text        NOT NULL,
    status              text        NOT NULL,
    -- A safe, machine-readable code. Never a stack trace or document content.
    error_code          text,
    chunk_count         integer     NOT NULL DEFAULT 0,
    uploaded_by_user_id uuid REFERENCES users (id),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    processed_at        timestamptz,
    CONSTRAINT uq_documents_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_documents_lineage_version UNIQUE (tenant_id, lineage_id, version),
    CONSTRAINT ck_documents_version CHECK (version >= 1),
    CONSTRAINT ck_documents_status CHECK (
        status IN ('UPLOADED', 'QUEUED', 'PROCESSING', 'READY', 'FAILED', 'ARCHIVED', 'DELETED')),
    CONSTRAINT ck_documents_mime CHECK (detected_mime IN ('application/pdf', 'text/plain', 'text/markdown')),
    CONSTRAINT fk_documents_source_same_tenant
        FOREIGN KEY (tenant_id, source_id) REFERENCES knowledge_sources (tenant_id, id)
);
CREATE INDEX ix_documents_tenant_status ON documents (tenant_id, status);
CREATE INDEX ix_documents_tenant_created ON documents (tenant_id, created_at DESC);
CREATE INDEX ix_documents_tenant_hash ON documents (tenant_id, content_hash);

-- The embedding column has no fixed dimension so a model change does not need a schema change.
-- Each row records the model, revision and dimension that produced it, and retrieval must compare
-- only vectors from the same embedding profile.
CREATE TABLE document_chunks (
    id                  uuid PRIMARY KEY,
    tenant_id           uuid        NOT NULL REFERENCES tenants (id),
    document_id         uuid        NOT NULL,
    chunk_index         integer     NOT NULL,
    content             text        NOT NULL,
    page_number         integer,
    heading             text,
    embedding           vector      NOT NULL,
    embedding_model     text        NOT NULL,
    embedding_revision  text        NOT NULL,
    embedding_dimension integer     NOT NULL,
    -- Only active chunks are eligible for retrieval.
    active              boolean     NOT NULL DEFAULT true,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_document_chunks_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_document_chunks_position UNIQUE (tenant_id, document_id, chunk_index),
    CONSTRAINT ck_document_chunks_dimension CHECK (vector_dims(embedding) = embedding_dimension),
    CONSTRAINT fk_document_chunks_document_same_tenant
        FOREIGN KEY (tenant_id, document_id) REFERENCES documents (tenant_id, id)
);
-- Exact search with a tenant filter. No approximate index until a benchmark justifies one (ADR-003).
CREATE INDEX ix_document_chunks_tenant_active ON document_chunks (tenant_id) WHERE active;

-- Events written in the same transaction as the change they describe, delivered afterwards (ADR-010).
CREATE TABLE outbox_events (
    id              uuid PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenants (id),
    event_type      text        NOT NULL,
    aggregate_id    uuid        NOT NULL,
    payload         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    status          text        NOT NULL DEFAULT 'PENDING',
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error      text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    delivered_at    timestamptz,
    CONSTRAINT uq_outbox_events_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_outbox_events_type CHECK (event_type IN ('DOCUMENT_INGEST', 'DOCUMENT_STORAGE_DELETE')),
    CONSTRAINT ck_outbox_events_status CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED'))
);
CREATE INDEX ix_outbox_events_pending ON outbox_events (next_attempt_at) WHERE status = 'PENDING';

-- Set transaction-locally by the outbox dispatcher only (ADR-016).
CREATE FUNCTION app_outbox_dispatcher() RETURNS boolean
    LANGUAGE sql STABLE
AS $$ SELECT COALESCE(current_setting('app.outbox_dispatcher', true), '') = 'on' $$;

ALTER TABLE knowledge_sources ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_sources FORCE ROW LEVEL SECURITY;
ALTER TABLE documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE documents FORCE ROW LEVEL SECURITY;
ALTER TABLE document_chunks ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_chunks FORCE ROW LEVEL SECURITY;
ALTER TABLE outbox_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE outbox_events FORCE ROW LEVEL SECURITY;

CREATE POLICY knowledge_sources_select ON knowledge_sources FOR SELECT USING (tenant_id = app_current_tenant_id());
CREATE POLICY knowledge_sources_insert ON knowledge_sources FOR INSERT
    WITH CHECK (tenant_id = app_current_tenant_id());
CREATE POLICY knowledge_sources_update ON knowledge_sources FOR UPDATE
    USING (tenant_id = app_current_tenant_id()) WITH CHECK (tenant_id = app_current_tenant_id());

CREATE POLICY documents_select ON documents FOR SELECT USING (tenant_id = app_current_tenant_id());
CREATE POLICY documents_insert ON documents FOR INSERT WITH CHECK (tenant_id = app_current_tenant_id());
CREATE POLICY documents_update ON documents FOR UPDATE
    USING (tenant_id = app_current_tenant_id()) WITH CHECK (tenant_id = app_current_tenant_id());

CREATE POLICY document_chunks_select ON document_chunks FOR SELECT USING (tenant_id = app_current_tenant_id());
CREATE POLICY document_chunks_insert ON document_chunks FOR INSERT
    WITH CHECK (tenant_id = app_current_tenant_id());
CREATE POLICY document_chunks_update ON document_chunks FOR UPDATE
    USING (tenant_id = app_current_tenant_id()) WITH CHECK (tenant_id = app_current_tenant_id());
-- Chunks are removed when their document is deleted or re-indexed.
CREATE POLICY document_chunks_delete ON document_chunks FOR DELETE USING (tenant_id = app_current_tenant_id());

-- The dispatcher runs outside any request, so it has no tenant. Its dedicated setting lets it see
-- pending events of every tenant. It unlocks reading only: every write still requires the event's
-- own tenant to be bound first.
CREATE POLICY outbox_events_select ON outbox_events FOR SELECT
    USING (tenant_id = app_current_tenant_id() OR app_outbox_dispatcher());
CREATE POLICY outbox_events_insert ON outbox_events FOR INSERT WITH CHECK (tenant_id = app_current_tenant_id());
CREATE POLICY outbox_events_update ON outbox_events FOR UPDATE
    USING (tenant_id = app_current_tenant_id()) WITH CHECK (tenant_id = app_current_tenant_id());

GRANT SELECT, INSERT, UPDATE ON knowledge_sources TO ${app_role};
GRANT SELECT, INSERT, UPDATE ON documents TO ${app_role};
GRANT SELECT, DELETE ON document_chunks TO ${app_role};
GRANT UPDATE (active) ON document_chunks TO ${app_role};
GRANT SELECT, INSERT, UPDATE ON outbox_events TO ${app_role};

-- The worker: nothing outside knowledge, and only the processing columns of a document.
GRANT USAGE ON SCHEMA public TO ${worker_role};
GRANT SELECT ON documents TO ${worker_role};
GRANT UPDATE (status, error_code, chunk_count, processed_at, updated_at) ON documents TO ${worker_role};
GRANT SELECT, INSERT, DELETE ON document_chunks TO ${worker_role};
GRANT UPDATE (active) ON document_chunks TO ${worker_role};

-- Workspaces created before this migration get the default knowledge source new ones receive.
INSERT INTO knowledge_sources (id, tenant_id, name, source_type, status)
SELECT gen_random_uuid(), t.id, 'Uploaded documents', 'UPLOAD', 'ACTIVE'
FROM tenants t;
