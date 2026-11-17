-- M3: the AI service's read-only access to knowledge, and the record of every AI interaction.

-- The AI service role can read chunks and the few document columns retrieval needs. It cannot
-- write anything, and Row-Level Security applies to it like any other runtime role.
GRANT SELECT ON document_chunks TO ${ai_role};
GRANT SELECT (id, tenant_id, original_filename, status) ON documents TO ${ai_role};

-- One row per question put to the assistant: what was retrieved, what was answered and by which
-- model and prompt version, so any answer can be traced and evaluated later. Rows are append-only.
CREATE TABLE ai_interactions (
    id                 uuid PRIMARY KEY,
    tenant_id          uuid        NOT NULL REFERENCES tenants (id),
    source             text        NOT NULL,
    assistant_version  integer     NOT NULL,
    created_by_user_id uuid        REFERENCES users (id),
    question           text        NOT NULL,
    answer             text        NOT NULL,
    outcome            text        NOT NULL,
    intent             text,
    needs_escalation   boolean     NOT NULL,
    escalation_reason  text,
    -- [{chunk_id, document_id, score, used}] for every retrieved chunk.
    retrieval          jsonb       NOT NULL DEFAULT '[]'::jsonb,
    -- Chunk ids the answer cites; always a subset of the retrieved ids.
    cited_chunk_ids    uuid[]      NOT NULL DEFAULT '{}',
    provider           text        NOT NULL,
    model              text,
    prompt_version     text        NOT NULL,
    embedding_model    text        NOT NULL,
    evidence_threshold real        NOT NULL,
    latency_ms         integer     NOT NULL,
    input_tokens       integer     NOT NULL DEFAULT 0,
    output_tokens      integer     NOT NULL DEFAULT 0,
    created_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_ai_interactions_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_ai_interactions_source CHECK (source IN ('PLAYGROUND')),
    CONSTRAINT ck_ai_interactions_outcome CHECK (outcome IN (
        'ANSWERED', 'ESCALATED', 'NO_EVIDENCE', 'PROVIDER_UNAVAILABLE', 'INVALID_OUTPUT',
        'INVALID_CITATION', 'UNGROUNDED', 'REFUSED', 'SERVICE_UNAVAILABLE'))
);
CREATE INDEX ix_ai_interactions_tenant_created ON ai_interactions (tenant_id, created_at DESC);

ALTER TABLE ai_interactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_interactions FORCE ROW LEVEL SECURITY;

CREATE POLICY ai_interactions_select ON ai_interactions FOR SELECT USING (tenant_id = app_current_tenant_id());
CREATE POLICY ai_interactions_insert ON ai_interactions FOR INSERT
    WITH CHECK (tenant_id = app_current_tenant_id());

GRANT SELECT, INSERT ON ai_interactions TO ${app_role};
