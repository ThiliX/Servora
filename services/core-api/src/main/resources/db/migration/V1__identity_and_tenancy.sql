-- M1: identity, tenants, memberships, invitations, audit events.
--
-- Run by the owner role (Flyway). The runtime role ${app_role} is created outside
-- migrations (infrastructure/database/init) and receives only the grants below.
-- It owns nothing and has no BYPASSRLS, so every policy here applies to it.

-- ---------------------------------------------------------------------------
-- Request context helpers. The application sets these transaction-locally with
-- set_config(name, value, true). An unset or empty setting yields NULL, and a
-- comparison with NULL is never true, so "no context" means "no rows".
-- ---------------------------------------------------------------------------
CREATE FUNCTION app_current_tenant_id() RETURNS uuid
    LANGUAGE sql STABLE
AS $$ SELECT NULLIF(current_setting('app.tenant_id', true), '')::uuid $$;

CREATE FUNCTION app_current_user_id() RETURNS uuid
    LANGUAGE sql STABLE
AS $$ SELECT NULLIF(current_setting('app.user_id', true), '')::uuid $$;

CREATE FUNCTION app_current_invitation_token_hash() RETURNS text
    LANGUAGE sql STABLE
AS $$ SELECT NULLIF(current_setting('app.invitation_token_hash', true), '') $$;

-- ---------------------------------------------------------------------------
-- Global identity tables (not tenant-owned).
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id                uuid PRIMARY KEY,
    email             text        NOT NULL,
    password_hash     text        NOT NULL,
    display_name      text        NOT NULL,
    status            text        NOT NULL,
    email_verified_at timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_email_normalized CHECK (email = lower(btrim(email))),
    CONSTRAINT ck_users_status CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'DISABLED'))
);

-- Platform administration is a separate permission domain from workspace roles.
CREATE TABLE platform_admins (
    user_id    uuid PRIMARY KEY REFERENCES users (id),
    granted_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE email_verification_tokens (
    id         uuid PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash text        NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_email_verification_tokens_hash UNIQUE (token_hash)
);

CREATE TABLE refresh_tokens (
    id         uuid PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id  uuid        NOT NULL,
    token_hash text        NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash)
);
CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);

-- ---------------------------------------------------------------------------
-- Tenant root.
-- ---------------------------------------------------------------------------
CREATE TABLE tenants (
    id               uuid PRIMARY KEY,
    name             text        NOT NULL,
    slug             text        NOT NULL,
    status           text        NOT NULL,
    timezone         text        NOT NULL DEFAULT 'UTC',
    default_language text        NOT NULL DEFAULT 'en',
    industry         text,
    logo_url         text,
    plan_code        text        NOT NULL DEFAULT 'FREE',
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenants_slug UNIQUE (slug),
    CONSTRAINT ck_tenants_slug CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,48}[a-z0-9]$'),
    CONSTRAINT ck_tenants_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

-- ---------------------------------------------------------------------------
-- Tenant-owned tables. Each has tenant_id NOT NULL and UNIQUE (tenant_id, id)
-- so children can reference the parent inside the same tenant.
-- ---------------------------------------------------------------------------
CREATE TABLE memberships (
    id         uuid PRIMARY KEY,
    tenant_id  uuid        NOT NULL REFERENCES tenants (id),
    user_id    uuid        NOT NULL REFERENCES users (id),
    role       text        NOT NULL,
    status     text        NOT NULL,
    joined_at  timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_memberships_tenant_user UNIQUE (tenant_id, user_id),
    CONSTRAINT uq_memberships_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_memberships_role CHECK (role IN ('OWNER', 'ADMIN', 'SUPERVISOR', 'AGENT')),
    CONSTRAINT ck_memberships_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);
CREATE INDEX ix_memberships_user ON memberships (user_id);

CREATE TABLE invitations (
    id                       uuid PRIMARY KEY,
    tenant_id                uuid        NOT NULL REFERENCES tenants (id),
    email                    text        NOT NULL,
    role                     text        NOT NULL,
    token_hash               text        NOT NULL,
    status                   text        NOT NULL,
    expires_at               timestamptz NOT NULL,
    accepted_at              timestamptz,
    invited_by_membership_id uuid        NOT NULL,
    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_at               timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_invitations_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_invitations_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_invitations_email_normalized CHECK (email = lower(btrim(email))),
    CONSTRAINT ck_invitations_role CHECK (role IN ('ADMIN', 'SUPERVISOR', 'AGENT')),
    CONSTRAINT ck_invitations_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED')),
    CONSTRAINT fk_invitations_inviter_same_tenant
        FOREIGN KEY (tenant_id, invited_by_membership_id) REFERENCES memberships (tenant_id, id)
);
CREATE UNIQUE INDEX uq_invitations_pending_email ON invitations (tenant_id, email) WHERE status = 'PENDING';
CREATE INDEX ix_invitations_tenant_created ON invitations (tenant_id, created_at);

CREATE TABLE audit_events (
    id            uuid PRIMARY KEY,
    tenant_id     uuid        NOT NULL REFERENCES tenants (id),
    actor_user_id uuid REFERENCES users (id),
    action        text        NOT NULL,
    resource_type text        NOT NULL,
    resource_id   uuid,
    metadata      jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_audit_events_tenant_id UNIQUE (tenant_id, id)
);
CREATE INDEX ix_audit_events_tenant_created ON audit_events (tenant_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- Row-Level Security. FORCE makes the policies apply to the table owner too.
-- ---------------------------------------------------------------------------
ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenants FORCE ROW LEVEL SECURITY;
ALTER TABLE memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE memberships FORCE ROW LEVEL SECURITY;
ALTER TABLE invitations ENABLE ROW LEVEL SECURITY;
ALTER TABLE invitations FORCE ROW LEVEL SECURITY;
ALTER TABLE audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_events FORCE ROW LEVEL SECURITY;

-- memberships: visible inside the current tenant. A user can additionally see
-- their own membership rows, which is what lets the backend validate membership
-- before it sets the tenant context, and list the workspaces a user belongs to.
CREATE POLICY memberships_select ON memberships FOR SELECT
    USING (tenant_id = app_current_tenant_id() OR user_id = app_current_user_id());
CREATE POLICY memberships_insert ON memberships FOR INSERT
    WITH CHECK (tenant_id = app_current_tenant_id());
CREATE POLICY memberships_update ON memberships FOR UPDATE
    USING (tenant_id = app_current_tenant_id())
    WITH CHECK (tenant_id = app_current_tenant_id());

-- tenants: the current tenant, or a tenant where the current user is an active member.
CREATE POLICY tenants_select ON tenants FOR SELECT
    USING (
        id = app_current_tenant_id()
        OR EXISTS (
            SELECT 1
            FROM memberships m
            WHERE m.tenant_id = tenants.id
              AND m.user_id = app_current_user_id()
              AND m.status = 'ACTIVE'
        )
    );
CREATE POLICY tenants_insert ON tenants FOR INSERT
    WITH CHECK (id = app_current_tenant_id());
CREATE POLICY tenants_update ON tenants FOR UPDATE
    USING (id = app_current_tenant_id())
    WITH CHECK (id = app_current_tenant_id());

-- invitations: visible inside the current tenant, or to a caller who presents
-- the invitation token (its hash is set as transaction-local context).
CREATE POLICY invitations_select ON invitations FOR SELECT
    USING (tenant_id = app_current_tenant_id() OR token_hash = app_current_invitation_token_hash());
CREATE POLICY invitations_insert ON invitations FOR INSERT
    WITH CHECK (tenant_id = app_current_tenant_id());
CREATE POLICY invitations_update ON invitations FOR UPDATE
    USING (tenant_id = app_current_tenant_id())
    WITH CHECK (tenant_id = app_current_tenant_id());

-- audit_events: append-only within the current tenant.
CREATE POLICY audit_events_select ON audit_events FOR SELECT
    USING (tenant_id = app_current_tenant_id());
CREATE POLICY audit_events_insert ON audit_events FOR INSERT
    WITH CHECK (tenant_id = app_current_tenant_id());

-- No DELETE policies exist, so deletes are denied on every tenant-owned table.

-- ---------------------------------------------------------------------------
-- Grants for the runtime role: the minimum each table needs.
-- ---------------------------------------------------------------------------
GRANT USAGE ON SCHEMA public TO ${app_role};
GRANT SELECT, INSERT, UPDATE ON users TO ${app_role};
GRANT SELECT ON platform_admins TO ${app_role};
GRANT SELECT, INSERT, UPDATE ON email_verification_tokens TO ${app_role};
GRANT SELECT, INSERT, UPDATE ON refresh_tokens TO ${app_role};
GRANT SELECT, INSERT, UPDATE ON tenants TO ${app_role};
GRANT SELECT, INSERT, UPDATE ON memberships TO ${app_role};
GRANT SELECT, INSERT, UPDATE ON invitations TO ${app_role};
GRANT SELECT, INSERT ON audit_events TO ${app_role};
