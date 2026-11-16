-- M2 (part 1): AI assistant configuration and widget configuration.
-- Every table is tenant-owned: tenant_id NOT NULL, UNIQUE (tenant_id, id), tenant-aware foreign
-- keys, and forced Row-Level Security on app.tenant_id.

CREATE TABLE ai_assistants (
    id                 uuid PRIMARY KEY,
    tenant_id          uuid        NOT NULL REFERENCES tenants (id),
    name               text        NOT NULL,
    welcome_message    text        NOT NULL,
    fallback_message   text        NOT NULL,
    language           text        NOT NULL,
    tone               text        NOT NULL,
    automation_enabled boolean     NOT NULL DEFAULT true,
    confidence_policy  text        NOT NULL,
    current_version    integer     NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    -- One assistant per workspace for the MVP. Drop this constraint to allow more.
    CONSTRAINT uq_ai_assistants_tenant UNIQUE (tenant_id),
    CONSTRAINT uq_ai_assistants_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_ai_assistants_tone CHECK (tone IN ('FRIENDLY', 'PROFESSIONAL', 'CONCISE')),
    CONSTRAINT ck_ai_assistants_confidence CHECK (confidence_policy IN ('STRICT', 'BALANCED')),
    CONSTRAINT ck_ai_assistants_version CHECK (current_version >= 1)
);

-- Immutable configuration snapshots. The runtime role can insert and read them, never change them.
CREATE TABLE assistant_versions (
    id                 uuid PRIMARY KEY,
    tenant_id          uuid        NOT NULL REFERENCES tenants (id),
    assistant_id       uuid        NOT NULL,
    version            integer     NOT NULL,
    config             jsonb       NOT NULL,
    created_by_user_id uuid REFERENCES users (id),
    created_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_assistant_versions_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_assistant_versions_number UNIQUE (tenant_id, assistant_id, version),
    CONSTRAINT fk_assistant_versions_assistant_same_tenant
        FOREIGN KEY (tenant_id, assistant_id) REFERENCES ai_assistants (tenant_id, id)
);

CREATE TABLE widgets (
    id            uuid PRIMARY KEY,
    tenant_id     uuid        NOT NULL REFERENCES tenants (id),
    name          text        NOT NULL,
    -- An identifier embedded in customer websites. It is not a secret and grants nothing by itself.
    public_key    text        NOT NULL,
    status        text        NOT NULL,
    theme_color   text        NOT NULL,
    position      text        NOT NULL,
    title         text        NOT NULL,
    show_branding boolean     NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    -- One widget per workspace for the MVP. Drop this constraint to allow more.
    CONSTRAINT uq_widgets_tenant UNIQUE (tenant_id),
    CONSTRAINT uq_widgets_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_widgets_public_key UNIQUE (public_key),
    CONSTRAINT ck_widgets_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_widgets_theme_color CHECK (theme_color ~ '^#[0-9a-f]{6}$'),
    CONSTRAINT ck_widgets_position CHECK (position IN ('BOTTOM_RIGHT', 'BOTTOM_LEFT'))
);

-- Normalised origins (scheme://host[:port]) compared by exact equality, never by substring.
CREATE TABLE widget_allowed_origins (
    id         uuid PRIMARY KEY,
    tenant_id  uuid        NOT NULL REFERENCES tenants (id),
    widget_id  uuid        NOT NULL,
    origin     text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_widget_allowed_origins_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_widget_allowed_origins_origin UNIQUE (tenant_id, widget_id, origin),
    CONSTRAINT ck_widget_allowed_origins_format CHECK (origin ~ '^https?://[a-z0-9.-]+(:[0-9]{1,5})?$'),
    CONSTRAINT fk_widget_allowed_origins_widget_same_tenant
        FOREIGN KEY (tenant_id, widget_id) REFERENCES widgets (tenant_id, id)
);

ALTER TABLE ai_assistants ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_assistants FORCE ROW LEVEL SECURITY;
ALTER TABLE assistant_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE assistant_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE widgets ENABLE ROW LEVEL SECURITY;
ALTER TABLE widgets FORCE ROW LEVEL SECURITY;
ALTER TABLE widget_allowed_origins ENABLE ROW LEVEL SECURITY;
ALTER TABLE widget_allowed_origins FORCE ROW LEVEL SECURITY;

CREATE POLICY ai_assistants_select ON ai_assistants FOR SELECT USING (tenant_id = app_current_tenant_id());
CREATE POLICY ai_assistants_insert ON ai_assistants FOR INSERT WITH CHECK (tenant_id = app_current_tenant_id());
CREATE POLICY ai_assistants_update ON ai_assistants FOR UPDATE
    USING (tenant_id = app_current_tenant_id()) WITH CHECK (tenant_id = app_current_tenant_id());

CREATE POLICY assistant_versions_select ON assistant_versions FOR SELECT USING (tenant_id = app_current_tenant_id());
CREATE POLICY assistant_versions_insert ON assistant_versions FOR INSERT
    WITH CHECK (tenant_id = app_current_tenant_id());

CREATE POLICY widgets_select ON widgets FOR SELECT USING (tenant_id = app_current_tenant_id());
CREATE POLICY widgets_insert ON widgets FOR INSERT WITH CHECK (tenant_id = app_current_tenant_id());
CREATE POLICY widgets_update ON widgets FOR UPDATE
    USING (tenant_id = app_current_tenant_id()) WITH CHECK (tenant_id = app_current_tenant_id());

CREATE POLICY widget_allowed_origins_select ON widget_allowed_origins FOR SELECT
    USING (tenant_id = app_current_tenant_id());
CREATE POLICY widget_allowed_origins_insert ON widget_allowed_origins FOR INSERT
    WITH CHECK (tenant_id = app_current_tenant_id());
-- The allowed-origin list is replaced as a whole, so its rows may be deleted within the tenant.
CREATE POLICY widget_allowed_origins_delete ON widget_allowed_origins FOR DELETE
    USING (tenant_id = app_current_tenant_id());

GRANT SELECT, INSERT, UPDATE ON ai_assistants TO ${app_role};
GRANT SELECT, INSERT ON assistant_versions TO ${app_role};
GRANT SELECT, INSERT, UPDATE ON widgets TO ${app_role};
GRANT SELECT, INSERT, DELETE ON widget_allowed_origins TO ${app_role};

-- Workspaces created before this migration get the same defaults new workspaces receive.
-- Runs as the migration owner; the application creates these rows itself from now on.
INSERT INTO ai_assistants (id, tenant_id, name, welcome_message, fallback_message, language, tone,
                           automation_enabled, confidence_policy, current_version)
SELECT gen_random_uuid(), t.id, t.name || ' Assistant',
       'Hi! How can I help you today?',
       'I am not sure about that. Would you like to talk to a member of our team?',
       t.default_language, 'FRIENDLY', true, 'STRICT', 1
FROM tenants t;

INSERT INTO assistant_versions (id, tenant_id, assistant_id, version, config)
SELECT gen_random_uuid(), a.tenant_id, a.id, 1,
       jsonb_build_object(
           'name', a.name,
           'welcome_message', a.welcome_message,
           'fallback_message', a.fallback_message,
           'language', a.language,
           'tone', a.tone,
           'automation_enabled', a.automation_enabled,
           'confidence_policy', a.confidence_policy)
FROM ai_assistants a;

INSERT INTO widgets (id, tenant_id, name, public_key, status, theme_color, position, title, show_branding)
SELECT gen_random_uuid(), t.id, 'Website widget',
       'wk_' || replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', ''),
       'ACTIVE', '#4f46e5', 'BOTTOM_RIGHT', t.name, true
FROM tenants t;
