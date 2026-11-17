-- M6: plans and subscriptions, usage metering, retention and deletion, and platform administration.

-- ---------------------------------------------------------------------------
-- Plans: a global catalogue, not tenant-owned. Limits apply per calendar month (UTC) except
-- max_documents, which caps the documents a workspace holds at any time. There is no payment
-- provider (ADR-019): a platform operator assigns the plan.
-- ---------------------------------------------------------------------------
CREATE TABLE plans (
    code                  text PRIMARY KEY,
    name                  text    NOT NULL,
    monthly_ai_requests   integer NOT NULL,
    monthly_tokens        bigint  NOT NULL,
    monthly_conversations integer NOT NULL,
    max_documents         integer NOT NULL,
    sort_order            integer NOT NULL,
    CONSTRAINT ck_plans_code CHECK (code ~ '^[A-Z][A-Z0-9_]{1,31}$'),
    CONSTRAINT ck_plans_limits CHECK (
        monthly_ai_requests >= 0 AND monthly_tokens >= 0 AND monthly_conversations >= 0 AND max_documents >= 0)
);

INSERT INTO plans (code, name, monthly_ai_requests, monthly_tokens, monthly_conversations, max_documents, sort_order)
VALUES ('FREE', 'Free', 300, 500000, 200, 20, 1),
       ('STARTER', 'Starter', 3000, 5000000, 2000, 100, 2),
       ('GROWTH', 'Growth', 20000, 40000000, 15000, 500, 3);

-- ---------------------------------------------------------------------------
-- One subscription per workspace. It replaces tenants.plan_code, so the plan has one source of truth.
-- ---------------------------------------------------------------------------
CREATE TABLE subscriptions (
    id         uuid PRIMARY KEY,
    tenant_id  uuid        NOT NULL REFERENCES tenants (id),
    plan_code  text        NOT NULL REFERENCES plans (code),
    status     text        NOT NULL DEFAULT 'ACTIVE',
    started_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_subscriptions_tenant UNIQUE (tenant_id),
    CONSTRAINT uq_subscriptions_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_subscriptions_status CHECK (status IN ('ACTIVE'))
);

INSERT INTO subscriptions (id, tenant_id, plan_code)
SELECT gen_random_uuid(), t.id, CASE WHEN p.code IS NULL THEN 'FREE' ELSE t.plan_code END
FROM tenants t LEFT JOIN plans p ON p.code = t.plan_code;

ALTER TABLE tenants DROP COLUMN plan_code;

-- Conversations whose last activity is older than this many days are deleted. NULL keeps them.
ALTER TABLE tenants ADD COLUMN conversation_retention_days integer;
ALTER TABLE tenants ADD CONSTRAINT ck_tenants_retention
    CHECK (conversation_retention_days IS NULL OR conversation_retention_days BETWEEN 30 AND 3650);
-- Why a workspace is suspended, written by a platform operator. Shown to its members.
ALTER TABLE tenants ADD COLUMN suspended_reason text;
ALTER TABLE tenants ADD CONSTRAINT ck_tenants_suspended_reason
    CHECK (suspended_reason IS NULL OR char_length(suspended_reason) <= 500);

-- ---------------------------------------------------------------------------
-- Usage events: append-only metering. One row per AI request (with tokens and estimated cost),
-- per uploaded document and per started conversation. Never holds message or document content.
-- ---------------------------------------------------------------------------
CREATE TABLE usage_events (
    id                    uuid PRIMARY KEY,
    tenant_id             uuid        NOT NULL REFERENCES tenants (id),
    event_type            text        NOT NULL,
    -- For AI_REQUEST: PLAYGROUND, WIDGET or AGENT_SUGGESTION.
    source                text,
    input_tokens          integer     NOT NULL DEFAULT 0,
    output_tokens         integer     NOT NULL DEFAULT 0,
    -- Millionths of a US dollar, from the configured price per million tokens. An estimate only.
    estimated_cost_micros bigint      NOT NULL DEFAULT 0,
    provider              text,
    model                 text,
    -- The AI interaction, document or conversation the event is about. No foreign key: usage
    -- history must survive the deletion of what it measured.
    reference_id          uuid,
    occurred_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_usage_events_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_usage_events_type CHECK (event_type IN ('AI_REQUEST', 'DOCUMENT_UPLOADED', 'CONVERSATION_STARTED')),
    CONSTRAINT ck_usage_events_source CHECK (
        (event_type = 'AI_REQUEST' AND source IN ('PLAYGROUND', 'WIDGET', 'AGENT_SUGGESTION'))
        OR (event_type <> 'AI_REQUEST' AND source IS NULL)),
    CONSTRAINT ck_usage_events_amounts CHECK (input_tokens >= 0 AND output_tokens >= 0 AND estimated_cost_micros >= 0)
);
CREATE INDEX ix_usage_events_tenant_type_time ON usage_events (tenant_id, event_type, occurred_at);

-- An AI request refused because the workspace reached its plan limit. The model is not called.
ALTER TABLE ai_interactions DROP CONSTRAINT ck_ai_interactions_outcome;
ALTER TABLE ai_interactions ADD CONSTRAINT ck_ai_interactions_outcome CHECK (outcome IN (
    'ANSWERED', 'ESCALATED', 'NO_EVIDENCE', 'PROVIDER_UNAVAILABLE', 'INVALID_OUTPUT',
    'INVALID_CITATION', 'UNGROUNDED', 'REFUSED', 'SERVICE_UNAVAILABLE', 'OVER_LIMIT'));

-- Analytics read conversations by creation time and interactions by source.
CREATE INDEX ix_conversations_tenant_created ON conversations (tenant_id, created_at);
CREATE INDEX ix_ai_interactions_tenant_source_created ON ai_interactions (tenant_id, source, created_at);

-- ---------------------------------------------------------------------------
-- Platform administration (ADR-019). A platform admin is a row in platform_admins, granted by an
-- operator with database access; the application cannot grant it. Cross-tenant reads need both
-- that row and the transaction-local flag that only the platform admin service sets, and they
-- cover workspace metadata, subscriptions and usage counts only, never conversation content.
-- ---------------------------------------------------------------------------
CREATE FUNCTION app_is_platform_admin() RETURNS boolean
    LANGUAGE sql STABLE
AS $$
    SELECT current_setting('app.platform_admin', true) = 'on'
       AND EXISTS (SELECT 1 FROM platform_admins WHERE user_id = app_current_user_id())
$$;

CREATE POLICY tenants_select_platform_admin ON tenants FOR SELECT USING (app_is_platform_admin());
CREATE POLICY tenants_update_platform_admin ON tenants FOR UPDATE
    USING (app_is_platform_admin()) WITH CHECK (app_is_platform_admin());

-- Platform-level audit trail: who did what to which workspace. The same actions are also written
-- to the workspace's own audit log, so its owners can see them.
CREATE TABLE platform_audit_events (
    id               uuid PRIMARY KEY,
    actor_user_id    uuid        NOT NULL REFERENCES users (id),
    action           text        NOT NULL,
    target_tenant_id uuid REFERENCES tenants (id),
    metadata         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_platform_audit_events_created ON platform_audit_events (created_at DESC);

-- The retention job reads, across workspaces, only the ids of workspaces that have a retention
-- period. It then binds each workspace in turn before touching its data.
CREATE POLICY tenants_select_retention_job ON tenants FOR SELECT
    USING (current_setting('app.retention_job', true) = 'on' AND conversation_retention_days IS NOT NULL);

-- ---------------------------------------------------------------------------
-- Row-Level Security for the new tables.
-- ---------------------------------------------------------------------------
ALTER TABLE subscriptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE subscriptions FORCE ROW LEVEL SECURITY;
ALTER TABLE usage_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE usage_events FORCE ROW LEVEL SECURITY;
ALTER TABLE platform_audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_audit_events FORCE ROW LEVEL SECURITY;

CREATE POLICY subscriptions_select ON subscriptions FOR SELECT
    USING (tenant_id = app_current_tenant_id() OR app_is_platform_admin());
CREATE POLICY subscriptions_insert ON subscriptions FOR INSERT WITH CHECK (tenant_id = app_current_tenant_id());
CREATE POLICY subscriptions_update_platform_admin ON subscriptions FOR UPDATE
    USING (app_is_platform_admin()) WITH CHECK (app_is_platform_admin());

CREATE POLICY usage_events_select ON usage_events FOR SELECT
    USING (tenant_id = app_current_tenant_id() OR app_is_platform_admin());
CREATE POLICY usage_events_insert ON usage_events FOR INSERT WITH CHECK (tenant_id = app_current_tenant_id());

CREATE POLICY platform_audit_events_select ON platform_audit_events FOR SELECT USING (app_is_platform_admin());
CREATE POLICY platform_audit_events_insert ON platform_audit_events FOR INSERT
    WITH CHECK (app_is_platform_admin() AND actor_user_id = app_current_user_id());

-- ---------------------------------------------------------------------------
-- Deleting one conversation and everything that belongs to it. The runtime role has no DELETE
-- privilege on any of these tables; this function is the one narrow way to delete, used by the
-- retention job and by owners. It acts only inside the workspace bound for the transaction.
-- It runs with its owner's rights, so every statement names the tenant explicitly.
-- ---------------------------------------------------------------------------
CREATE FUNCTION delete_conversation_data(p_conversation_id uuid) RETURNS boolean
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
DECLARE
    v_tenant uuid := app_current_tenant_id();
    v_customer uuid;
BEGIN
    IF v_tenant IS NULL THEN
        RAISE EXCEPTION 'no workspace bound' USING ERRCODE = 'insufficient_privilege';
    END IF;

    SELECT customer_id INTO v_customer
    FROM conversations WHERE tenant_id = v_tenant AND id = p_conversation_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RETURN false;
    END IF;

    DELETE FROM notification_events n
    WHERE n.tenant_id = v_tenant
      AND (n.conversation_id = p_conversation_id
           OR n.ticket_id IN (SELECT t.id FROM tickets t
                              WHERE t.tenant_id = v_tenant AND t.conversation_id = p_conversation_id));
    DELETE FROM ticket_events e
    WHERE e.tenant_id = v_tenant
      AND e.ticket_id IN (SELECT t.id FROM tickets t
                          WHERE t.tenant_id = v_tenant AND t.conversation_id = p_conversation_id);
    DELETE FROM tickets WHERE tenant_id = v_tenant AND conversation_id = p_conversation_id;
    DELETE FROM internal_notes WHERE tenant_id = v_tenant AND conversation_id = p_conversation_id;
    DELETE FROM conversation_feedback WHERE tenant_id = v_tenant AND conversation_id = p_conversation_id;
    DELETE FROM message_citations c
    WHERE c.tenant_id = v_tenant
      AND c.message_id IN (SELECT m.id FROM messages m
                           WHERE m.tenant_id = v_tenant AND m.conversation_id = p_conversation_id);

    -- One statement, so the foreign keys between messages and their AI interactions are checked
    -- only after both are gone.
    WITH deleted AS (
        DELETE FROM messages WHERE tenant_id = v_tenant AND conversation_id = p_conversation_id
        RETURNING interaction_id
    )
    DELETE FROM ai_interactions
    WHERE tenant_id = v_tenant AND id IN (SELECT interaction_id FROM deleted WHERE interaction_id IS NOT NULL);
    DELETE FROM conversations WHERE tenant_id = v_tenant AND id = p_conversation_id;
    DELETE FROM customers c
    WHERE c.tenant_id = v_tenant AND c.id = v_customer
      AND NOT EXISTS (SELECT 1 FROM conversations o WHERE o.tenant_id = v_tenant AND o.customer_id = v_customer);
    RETURN true;
END;
$$;

REVOKE ALL ON FUNCTION delete_conversation_data(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION delete_conversation_data(uuid) TO ${app_role};

-- ---------------------------------------------------------------------------
-- Grants.
-- ---------------------------------------------------------------------------
GRANT SELECT ON plans TO ${app_role};
GRANT SELECT, INSERT ON subscriptions TO ${app_role};
GRANT UPDATE (plan_code, updated_at) ON subscriptions TO ${app_role};
GRANT SELECT, INSERT ON usage_events TO ${app_role};
GRANT SELECT, INSERT ON platform_audit_events TO ${app_role};
