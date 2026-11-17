-- M4: anonymous customers, conversations, messages and citations, and public widget key resolution.

-- A public widget request names a widget by its public key before any tenant is known. This
-- setting, set only by TenantContext, lets that one transaction read the single widget whose key
-- matches. Everything else is read after the widget's own tenant has been bound (ADR-017).
CREATE FUNCTION app_current_widget_key() RETURNS text
    LANGUAGE sql STABLE
AS $$ SELECT NULLIF(current_setting('app.widget_public_key', true), '') $$;

CREATE POLICY widgets_select_by_public_key ON widgets FOR SELECT
    USING (public_key = app_current_widget_key());

-- An anonymous website visitor. No name, email or IP address is stored.
CREATE TABLE customers (
    id           uuid PRIMARY KEY,
    tenant_id    uuid        NOT NULL REFERENCES tenants (id),
    widget_id    uuid        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_customers_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_customers_widget_same_tenant
        FOREIGN KEY (tenant_id, widget_id) REFERENCES widgets (tenant_id, id)
);

CREATE TABLE conversations (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid        NOT NULL REFERENCES tenants (id),
    customer_id          uuid        NOT NULL,
    widget_id            uuid        NOT NULL,
    status               text        NOT NULL DEFAULT 'OPEN',
    -- Who may reply. Only AI_ACTIVE conversations receive AI replies.
    control_mode         text        NOT NULL DEFAULT 'AI_ACTIVE',
    -- Incremented on every change of status or control mode. An AI reply is delivered only if the
    -- version is unchanged since the reply was requested (ADR-014).
    version              integer     NOT NULL DEFAULT 1,
    -- The website the conversation was started from, as checked against the allowed origins.
    origin               text        NOT NULL,
    handoff_requested_at timestamptz,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    last_message_at      timestamptz,
    CONSTRAINT uq_conversations_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_conversations_status CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT ck_conversations_control_mode CHECK (control_mode IN ('AI_ACTIVE', 'HUMAN_PENDING', 'HUMAN_ACTIVE')),
    CONSTRAINT fk_conversations_customer_same_tenant
        FOREIGN KEY (tenant_id, customer_id) REFERENCES customers (tenant_id, id),
    CONSTRAINT fk_conversations_widget_same_tenant
        FOREIGN KEY (tenant_id, widget_id) REFERENCES widgets (tenant_id, id)
);
CREATE INDEX ix_conversations_tenant_last_message ON conversations (tenant_id, last_message_at DESC NULLS LAST);

ALTER TABLE ai_interactions DROP CONSTRAINT ck_ai_interactions_source;
ALTER TABLE ai_interactions ADD CONSTRAINT ck_ai_interactions_source CHECK (source IN ('PLAYGROUND', 'WIDGET'));

CREATE TABLE messages (
    id              uuid PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenants (id),
    conversation_id uuid        NOT NULL,
    sender_type     text        NOT NULL,
    -- INTERNAL messages are never returned by public endpoints.
    visibility      text        NOT NULL DEFAULT 'PUBLIC',
    -- SUPPRESSED: an AI reply withheld because the conversation changed while it was generated.
    delivery_status text        NOT NULL DEFAULT 'DELIVERED',
    body            text        NOT NULL,
    -- Client-supplied key on customer messages, so a retried submission is stored once.
    idempotency_key text,
    -- For an assistant reply, the customer message it answers.
    reply_to_id     uuid,
    interaction_id  uuid,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_messages_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_messages_idempotency UNIQUE (tenant_id, conversation_id, idempotency_key),
    CONSTRAINT ck_messages_sender CHECK (sender_type IN ('CUSTOMER', 'ASSISTANT', 'AGENT', 'SYSTEM')),
    CONSTRAINT ck_messages_visibility CHECK (visibility IN ('PUBLIC', 'INTERNAL')),
    CONSTRAINT ck_messages_delivery CHECK (delivery_status IN ('DELIVERED', 'SUPPRESSED')),
    CONSTRAINT ck_messages_suppressed_internal CHECK (delivery_status = 'DELIVERED' OR visibility = 'INTERNAL'),
    CONSTRAINT ck_messages_body_length CHECK (char_length(body) BETWEEN 1 AND 4000),
    CONSTRAINT fk_messages_conversation_same_tenant
        FOREIGN KEY (tenant_id, conversation_id) REFERENCES conversations (tenant_id, id),
    CONSTRAINT fk_messages_reply_to_same_tenant
        FOREIGN KEY (tenant_id, reply_to_id) REFERENCES messages (tenant_id, id),
    CONSTRAINT fk_messages_interaction_same_tenant
        FOREIGN KEY (tenant_id, interaction_id) REFERENCES ai_interactions (tenant_id, id)
);
CREATE INDEX ix_messages_conversation_created ON messages (tenant_id, conversation_id, created_at, id);
CREATE INDEX ix_messages_reply_to ON messages (tenant_id, reply_to_id) WHERE reply_to_id IS NOT NULL;

-- A snapshot of what was cited. No foreign key to the chunk: chunks are removed when a document is
-- deleted or re-indexed, and the record of what the customer was shown must survive that.
CREATE TABLE message_citations (
    id          uuid PRIMARY KEY,
    tenant_id   uuid        NOT NULL REFERENCES tenants (id),
    message_id  uuid        NOT NULL,
    position    integer     NOT NULL,
    chunk_id    uuid        NOT NULL,
    document_id uuid        NOT NULL,
    filename    text        NOT NULL,
    page_number integer,
    heading     text,
    snippet     text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_message_citations_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_message_citations_position UNIQUE (tenant_id, message_id, position),
    CONSTRAINT fk_message_citations_message_same_tenant
        FOREIGN KEY (tenant_id, message_id) REFERENCES messages (tenant_id, id)
);

ALTER TABLE customers ENABLE ROW LEVEL SECURITY;
ALTER TABLE customers FORCE ROW LEVEL SECURITY;
ALTER TABLE conversations ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversations FORCE ROW LEVEL SECURITY;
ALTER TABLE messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE messages FORCE ROW LEVEL SECURITY;
ALTER TABLE message_citations ENABLE ROW LEVEL SECURITY;
ALTER TABLE message_citations FORCE ROW LEVEL SECURITY;

CREATE POLICY customers_select ON customers FOR SELECT USING (tenant_id = app_current_tenant_id());
CREATE POLICY customers_insert ON customers FOR INSERT WITH CHECK (tenant_id = app_current_tenant_id());
CREATE POLICY customers_update ON customers FOR UPDATE
    USING (tenant_id = app_current_tenant_id()) WITH CHECK (tenant_id = app_current_tenant_id());

CREATE POLICY conversations_select ON conversations FOR SELECT USING (tenant_id = app_current_tenant_id());
CREATE POLICY conversations_insert ON conversations FOR INSERT WITH CHECK (tenant_id = app_current_tenant_id());
CREATE POLICY conversations_update ON conversations FOR UPDATE
    USING (tenant_id = app_current_tenant_id()) WITH CHECK (tenant_id = app_current_tenant_id());

CREATE POLICY messages_select ON messages FOR SELECT USING (tenant_id = app_current_tenant_id());
CREATE POLICY messages_insert ON messages FOR INSERT WITH CHECK (tenant_id = app_current_tenant_id());

CREATE POLICY message_citations_select ON message_citations FOR SELECT USING (tenant_id = app_current_tenant_id());
CREATE POLICY message_citations_insert ON message_citations FOR INSERT
    WITH CHECK (tenant_id = app_current_tenant_id());

-- Messages and citations are append-only for the application.
GRANT SELECT, INSERT ON customers TO ${app_role};
GRANT UPDATE (last_seen_at) ON customers TO ${app_role};
GRANT SELECT, INSERT, UPDATE ON conversations TO ${app_role};
GRANT SELECT, INSERT ON messages TO ${app_role};
GRANT SELECT, INSERT ON message_citations TO ${app_role};
