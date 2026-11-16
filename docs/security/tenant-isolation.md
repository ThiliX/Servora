# Tenant isolation: strategy and test evidence

This document describes how tenant isolation works as of M1 and lists the automated tests that exercise it. It is updated whenever a milestone adds a tenant-owned resource.

## Layers

| Layer | What it does | Where |
|---|---|---|
| Authentication | Identifies the user from a signed, short-lived access token | `common/security/SecurityConfig` |
| Authorization | Checks active membership and the required permission for the requested workspace | `membership/WorkspaceAccess`, `membership/Permission` |
| Scoped queries | Every repository method takes the tenant ID; none loads a tenant-owned row by ID alone | `*Repository` interfaces |
| Tenant context | Sets `app.tenant_id` transaction-locally, only after the checks above | `common/tenant/TenantContext` |
| Row-Level Security | PostgreSQL filters every read and write by the context, even if a query forgets its predicate | `V1__identity_and_tenancy.sql` |
| Tenant-aware keys | Composite foreign keys stop a child row referencing another tenant's parent | same migration |
| Least privilege | The runtime role owns nothing, cannot bypass RLS, and has no `DELETE` on tenant tables | same migration, `infrastructure/database/init` |

## Request flow

1. The access token is verified and the user ID taken from its subject.
2. `WorkspaceAccess.authorize` sets `app.user_id`. With only that set, RLS exposes the user's own membership rows and nothing else.
3. It loads the user's membership for the workspace ID in the URL. No active membership means `404 WORKSPACE_NOT_FOUND`, the same response as for a workspace that does not exist.
4. It checks the role against the required permission (`403 FORBIDDEN` if insufficient).
5. Only then does it set `app.tenant_id`.
6. The transaction ends and PostgreSQL discards both settings. The connection returns to the pool clean.

## Context settings

Policies read three transaction-local settings. All are set only by `TenantContext`.

| Setting | Set when | What it unlocks |
|---|---|---|
| `app.tenant_id` | After membership and permission are validated; for a tenant ID the server just generated; or for the tenant of an invitation whose token was presented and checked | Rows of that tenant |
| `app.user_id` | For every authenticated request | The user's own `memberships` rows, and `tenants` rows where they are an active member |
| `app.invitation_token_hash` | When a user presents an invitation token | The single `invitations` row with that token hash |

The second and third settings exist because some lookups must happen before the tenant is known. They are narrow by construction and covered by the tests below. See [ADR-015](../architecture/adr/0015-row-level-security-context-beyond-the-tenant-id.md).

## Tables not under RLS

`users`, `refresh_tokens`, `email_verification_tokens` and `platform_admins` are global identity tables, not tenant-owned. The runtime role can read `users` in full, so application code must expose users to a tenant only through a membership join. This is a known boundary that RLS does not defend.

## Automated tests

All of these run in `./mvnw verify` and in CI. They use a real PostgreSQL started by Testcontainers. The application connects as `servora_app`, created exactly as in production: no superuser, no `BYPASSRLS`, owner of nothing.

### Cross-tenant negative tests over HTTP — `TenantIsolationIntegrationTest`

Fixture: tenants TechStore and HotelPro, each with an owner and an agent.

| Resource | Attack | Expected |
|---|---|---|
| Workspace | Read or update the other tenant's workspace | 404; data unchanged |
| Workspace | Compare a foreign workspace with a non-existent one | Identical response |
| Workspace list | List workspaces | Only the caller's own |
| Members | List the other tenant's members | 404 |
| Members | Change another tenant's member ID through the caller's own workspace URL | 404; role unchanged |
| Invitations | List, create or revoke in the other tenant, including by ID substitution | 404; invitation still pending |
| Invitations | Accept another tenant's invitation token with the wrong account | 403; no membership created |
| Audit events | Read the other tenant's audit log | 404; own log contains no foreign IDs |
| Slug | Create a workspace with another tenant's slug | 409 without revealing the other workspace |
| Membership | User in two workspaces has one membership disabled | Loses that workspace only |

### Database-level tests — `RowLevelSecurityIntegrationTest`

These bypass the application and connect directly as the runtime role.

- The runtime role is not a superuser, cannot bypass RLS, cannot create roles or databases, and owns no tables.
- It cannot disable RLS or drop a policy.
- **Every table with a `tenant_id` column has RLS enabled, forced, and at least one policy.** This check reads the catalog, so it fails automatically if a future migration adds an unprotected tenant table.
- With no context set, no tenant-owned row is visible.
- With a tenant context, only that tenant's rows are visible.
- With a user context, only that user's memberships and workspaces are visible.
- Inserting a row for another tenant, or moving a row to another tenant, is rejected by RLS.
- Updating another tenant's rows affects zero rows.
- Deleting from any tenant-owned table is denied; audit events cannot be updated.
- An invitation token hash exposes exactly one invitation and no memberships or tenants.
- After commit and after rollback, the same physical connection has no context and sees no rows.

### Connection pool test — `PooledConnectionIsolationIntegrationTest`

The pool is limited to two connections. The test fires 320 requests from 12 threads, alternating between the two tenants and mixing reads, committed writes and rejected cross-tenant requests, so the same two connections are reused constantly. Every response must contain only the caller's tenant. Afterwards the test borrows both pooled connections and asserts each has no context and sees no tenant rows.

### Role tests — `RoleEnforcementIntegrationTest`, `PermissionTest`

The role matrix is asserted independently of its implementation, and enforced over HTTP for all four roles, including that an admin cannot change an owner or grant ownership.

## Results

Recorded on 2026-11-16, local run: 56 tests, 0 failures. CI results are on the repository's Actions page.

## Known gaps

- No test yet races two owners demoting each other; the last-owner rule relies on a row lock that is reviewed but not exercised concurrently.
- Global identity tables are outside RLS, as described above.
- A valid access token keeps working for up to 15 minutes after a user is disabled. Membership changes take effect immediately.
