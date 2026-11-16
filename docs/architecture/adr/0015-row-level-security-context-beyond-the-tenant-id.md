# ADR-015: Row-Level Security context beyond the tenant ID

- **Status:** Accepted (decided in M1)
- **Date:** 2026-11-16

## Context

ADR-002 scopes every tenant-owned table by the transaction-local setting `app.tenant_id`, set after the backend validates membership. Three lookups cannot work that way, because they happen before a tenant is established:

- Validating membership itself requires reading `memberships`, which is protected by the policy.
- Listing the workspaces a user belongs to spans tenants by definition.
- Accepting an invitation is done by someone who is not yet a member.

The usual escape hatches are a role that bypasses RLS or `SECURITY DEFINER` functions. Both put an unrestricted read path inside the application.

## Decision

Policies read two further transaction-local settings, set only by `TenantContext`:

- `app.user_id`: the authenticated user. `memberships` rows are visible where `user_id` matches, and `tenants` rows where that user has an active membership.
- `app.invitation_token_hash`: the hash of a presented invitation token. The one `invitations` row with that hash is visible.

Writes are never unlocked by these settings. Every `INSERT` and `UPDATE` policy still requires `app.tenant_id`.

## Consequences

- No part of the application can bypass RLS, and there are no `SECURITY DEFINER` functions to audit.
- A user can read their own membership rows in any tenant. That is the intended behaviour, not a leak.
- Knowing an invitation token hash exposes one invitation row. The token is a 256-bit random secret, only its hash is stored, and acceptance still checks status, expiry and the invitee email.
- Each new tenant-owned table needs only the plain tenant policy unless it has a genuine pre-tenant lookup, which then needs its own reviewed policy and tests.

## Revisit when

A table needs a pre-tenant lookup that cannot be expressed as "rows belonging to this user" or "the row matching this secret".
