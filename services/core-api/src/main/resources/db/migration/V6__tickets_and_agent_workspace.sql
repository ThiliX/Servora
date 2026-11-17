-- M5: escalation, tickets, the agent workspace, internal notes, customer feedback and notifications.

-- The handbook's name for "waiting for a person": the conversation has been escalated and a ticket exists.
ALTER TABLE conversations DROP CONSTRAINT ck_conversations_control_mode;
UPDATE conversations SET control_mode = 'ESCALATION_PENDING' WHERE control_mode = 'HUMAN_PENDING';
ALTER TABLE conversations ADD CONSTRAINT ck_conversations_control_mode
    CHECK (control_mode IN ('AI_ACTIVE', 'ESCALATION_PENDING', 'HUMAN_ACTIVE'));

-- Statement time, not transaction start: rows written after a row lock is acquired are ordered by
-- when they were actually written. The takeover race test relies on this.
ALTER TABLE messages ALTER COLUMN created_at SET DEFAULT clock_timestamp();

-- Agent replies record who wrote them; AI suggestions are stored but never shown to the customer
-- until an agent sends them, which creates a separate agent message.
ALTER TABLE messages ADD COLUMN author_membership_id uuid;
ALTER TABLE messages ADD COLUMN source_suggestion_id uuid;
ALTER TABLE messages ADD CONSTRAINT fk_messages_author_same_tenant
    FOREIGN KEY (tenant_id, author_membership_id) REFERENCES memberships (tenant_id, id);
ALTER TABLE messages ADD CONSTRAINT fk_messages_source_suggestion_same_tenant
    FOREIGN KEY (tenant_id, source_suggestion_id) REFERENCES messages (tenant_id, id);
ALTER TABLE messages DROP CONSTRAINT ck_messages_visibility;
ALTER TABLE messages DROP CONSTRAINT ck_messages_delivery;
ALTER TABLE messages DROP CONSTRAINT ck_messages_suppressed_internal;
ALTER TABLE messages ADD CONSTRAINT ck_messages_visibility_delivery CHECK (
    (visibility = 'PUBLIC' AND delivery_status = 'DELIVERED')
    OR (visibility = 'INTERNAL' AND delivery_status = 'SUPPRESSED')
    OR (visibility = 'AI_SUGGESTION' AND delivery_status = 'PENDING_APPROVAL'));
ALTER TABLE messages ADD CONSTRAINT ck_messages_agent_author
    CHECK (sender_type <> 'AGENT' OR author_membership_id IS NOT NULL);

ALTER TABLE ai_interactions DROP CONSTRAINT ck_ai_interactions_source;
ALTER TABLE ai_interactions ADD CONSTRAINT ck_ai_interactions_source
    CHECK (source IN ('PLAYGROUND', 'WIDGET', 'AGENT_SUGGESTION'));

-- Per-workspace ticket numbers. Incrementing this row also serialises ticket creation per workspace.
ALTER TABLE tenants ADD COLUMN next_ticket_number bigint NOT NULL DEFAULT 1;

CREATE TABLE tickets (
    id                     uuid PRIMARY KEY,
    tenant_id              uuid        NOT NULL REFERENCES tenants (id),
    number                 bigint      NOT NULL,
    conversation_id        uuid        NOT NULL,
    -- Deterministic per conversation, so repeated or parallel escalations create exactly one ticket.
    idempotency_key        text        NOT NULL,
    status                 text        NOT NULL DEFAULT 'OPEN',
    priority               text        NOT NULL DEFAULT 'NORMAL',
    reason                 text        NOT NULL,
    assignee_membership_id uuid,
    resolution_outcome     text,
    resolution_note        text,
    first_response_at      timestamptz,
    resolved_at            timestamptz,
    closed_at              timestamptz,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_tickets_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_tickets_number UNIQUE (tenant_id, number),
    CONSTRAINT uq_tickets_conversation UNIQUE (tenant_id, conversation_id),
    CONSTRAINT uq_tickets_idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT ck_tickets_status CHECK (status IN ('OPEN', 'IN_PROGRESS', 'WAITING_FOR_CUSTOMER', 'RESOLVED', 'CLOSED')),
    CONSTRAINT ck_tickets_priority CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    CONSTRAINT ck_tickets_reason CHECK (reason IN (
        'CUSTOMER_REQUEST', 'INSUFFICIENT_EVIDENCE', 'SENSITIVE_REQUEST', 'REPEATED_MISUNDERSTANDING',
        'SERVICE_ERROR', 'RISK_RULE', 'AGENT_INITIATED')),
    CONSTRAINT ck_tickets_outcome CHECK (resolution_outcome IS NULL OR resolution_outcome IN (
        'SOLVED', 'NOT_SOLVED', 'NO_REPLY_FROM_CUSTOMER', 'DUPLICATE')),
    CONSTRAINT ck_tickets_resolved_has_outcome
        CHECK (status NOT IN ('RESOLVED', 'CLOSED') OR resolution_outcome IS NOT NULL),
    CONSTRAINT fk_tickets_conversation_same_tenant
        FOREIGN KEY (tenant_id, conversation_id) REFERENCES conversations (tenant_id, id),
    CONSTRAINT fk_tickets_assignee_same_tenant
        FOREIGN KEY (tenant_id, assignee_membership_id) REFERENCES memberships (tenant_id, id)
);
CREATE INDEX ix_tickets_queue ON tickets (tenant_id, status, created_at);
CREATE INDEX ix_tickets_assignee ON tickets (tenant_id, assignee_membership_id) WHERE assignee_membership_id IS NOT NULL;

CREATE TABLE ticket_events (
    id                  uuid PRIMARY KEY,
    tenant_id           uuid        NOT NULL REFERENCES tenants (id),
    ticket_id           uuid        NOT NULL,
    event_type          text        NOT NULL,
    -- Null for events caused by the customer or the system.
    actor_membership_id uuid,
    from_value          text,
    to_value            text,
    created_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_ticket_events_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_ticket_events_type CHECK (event_type IN (
        'CREATED', 'ASSIGNED', 'STATUS_CHANGED', 'PRIORITY_CHANGED', 'TAKEN_OVER', 'RESOLVED', 'CLOSED')),
    CONSTRAINT fk_ticket_events_ticket_same_tenant
        FOREIGN KEY (tenant_id, ticket_id) REFERENCES tickets (tenant_id, id),
    CONSTRAINT fk_ticket_events_actor_same_tenant
        FOREIGN KEY (tenant_id, actor_membership_id) REFERENCES memberships (tenant_id, id)
);
CREATE INDEX ix_ticket_events_ticket ON ticket_events (tenant_id, ticket_id, created_at);

-- Kept apart from messages, so no query for customer-facing messages can ever return a note.
CREATE TABLE internal_notes (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid        NOT NULL REFERENCES tenants (id),
    conversation_id      uuid        NOT NULL,
    author_membership_id uuid        NOT NULL,
    body                 text        NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_internal_notes_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_internal_notes_body_length CHECK (char_length(body) BETWEEN 1 AND 4000),
    CONSTRAINT fk_internal_notes_conversation_same_tenant
        FOREIGN KEY (tenant_id, conversation_id) REFERENCES conversations (tenant_id, id),
    CONSTRAINT fk_internal_notes_author_same_tenant
        FOREIGN KEY (tenant_id, author_membership_id) REFERENCES memberships (tenant_id, id)
);
CREATE INDEX ix_internal_notes_conversation ON internal_notes (tenant_id, conversation_id, created_at);

CREATE TABLE conversation_feedback (
    id              uuid PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenants (id),
    conversation_id uuid        NOT NULL,
    rating          integer     NOT NULL,
    comment         text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_conversation_feedback_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_conversation_feedback_conversation UNIQUE (tenant_id, conversation_id),
    CONSTRAINT ck_conversation_feedback_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT ck_conversation_feedback_comment CHECK (comment IS NULL OR char_length(comment) <= 1000),
    CONSTRAINT fk_conversation_feedback_conversation_same_tenant
        FOREIGN KEY (tenant_id, conversation_id) REFERENCES conversations (tenant_id, id)
);

CREATE TABLE notification_events (
    id                      uuid PRIMARY KEY,
    tenant_id               uuid        NOT NULL REFERENCES tenants (id),
    recipient_membership_id uuid        NOT NULL,
    event_type              text        NOT NULL,
    ticket_id               uuid,
    conversation_id         uuid,
    created_at              timestamptz NOT NULL DEFAULT now(),
    read_at                 timestamptz,
    CONSTRAINT uq_notification_events_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_notification_events_type CHECK (event_type IN ('TICKET_ASSIGNED', 'CUSTOMER_MESSAGE')),
    CONSTRAINT fk_notification_events_recipient_same_tenant
        FOREIGN KEY (tenant_id, recipient_membership_id) REFERENCES memberships (tenant_id, id),
    CONSTRAINT fk_notification_events_ticket_same_tenant
        FOREIGN KEY (tenant_id, ticket_id) REFERENCES tickets (tenant_id, id),
    CONSTRAINT fk_notification_events_conversation_same_tenant
        FOREIGN KEY (tenant_id, conversation_id) REFERENCES conversations (tenant_id, id)
);
CREATE INDEX ix_notification_events_recipient
    ON notification_events (tenant_id, recipient_membership_id, created_at DESC);

ALTER TABLE tickets ENABLE ROW LEVEL SECURITY;
ALTER TABLE tickets FORCE ROW LEVEL SECURITY;
ALTER TABLE ticket_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE ticket_events FORCE ROW LEVEL SECURITY;
ALTER TABLE internal_notes ENABLE ROW LEVEL SECURITY;
ALTER TABLE internal_notes FORCE ROW LEVEL SECURITY;
ALTER TABLE conversation_feedback ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversation_feedback FORCE ROW LEVEL SECURITY;
ALTER TABLE notification_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_events FORCE ROW LEVEL SECURITY;

CREATE POLICY tickets_select ON tickets FOR SELECT USING (tenant_id = app_current_tenant_id());
CREATE POLICY tickets_insert ON tickets FOR INSERT WITH CHECK (tenant_id = app_current_tenant_id());
CREATE POLICY tickets_update ON tickets FOR UPDATE
    USING (tenant_id = app_current_tenant_id()) WITH CHECK (tenant_id = app_current_tenant_id());

CREATE POLICY ticket_events_select ON ticket_events FOR SELECT USING (tenant_id = app_current_tenant_id());
CREATE POLICY ticket_events_insert ON ticket_events FOR INSERT WITH CHECK (tenant_id = app_current_tenant_id());

CREATE POLICY internal_notes_select ON internal_notes FOR SELECT USING (tenant_id = app_current_tenant_id());
CREATE POLICY internal_notes_insert ON internal_notes FOR INSERT WITH CHECK (tenant_id = app_current_tenant_id());

CREATE POLICY conversation_feedback_select ON conversation_feedback FOR SELECT
    USING (tenant_id = app_current_tenant_id());
CREATE POLICY conversation_feedback_insert ON conversation_feedback FOR INSERT
    WITH CHECK (tenant_id = app_current_tenant_id());

CREATE POLICY notification_events_select ON notification_events FOR SELECT
    USING (tenant_id = app_current_tenant_id());
CREATE POLICY notification_events_insert ON notification_events FOR INSERT
    WITH CHECK (tenant_id = app_current_tenant_id());
CREATE POLICY notification_events_update ON notification_events FOR UPDATE
    USING (tenant_id = app_current_tenant_id()) WITH CHECK (tenant_id = app_current_tenant_id());

GRANT SELECT, INSERT, UPDATE ON tickets TO ${app_role};
GRANT SELECT, INSERT ON ticket_events TO ${app_role};
GRANT SELECT, INSERT ON internal_notes TO ${app_role};
GRANT SELECT, INSERT ON conversation_feedback TO ${app_role};
GRANT SELECT, INSERT ON notification_events TO ${app_role};
GRANT UPDATE (read_at) ON notification_events TO ${app_role};
