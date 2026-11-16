# Tenant isolation: strategy and test evidence

This document describes how tenant isolation works as of M2 and lists the automated tests that exercise it. It is updated whenever a milestone adds a tenant-owned resource.

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
| `app.outbox_dispatcher` | By the outbox dispatcher only | Reading `outbox_events` of any tenant. No writes, no other table |

The settings other than `app.tenant_id` exist because some work happens before, or outside, a tenant context. They are narrow by construction and covered by the tests below. See [ADR-015](../architecture/adr/0015-row-level-security-context-beyond-the-tenant-id.md) and [ADR-016](../architecture/adr/0016-outbox-dispatcher-reads-across-tenants.md).

## The document worker

The worker is a separate Python service with its own database role, `servora_worker`. It is unprivileged in the same way as the application role and is granted only what ingestion needs: it can read documents, update a document's processing columns, and write chunks. It cannot read users, memberships, tenants, invitations, audit events, the outbox, assistants or widgets.

The worker learns which tenant and document to process from a short-lived token signed by the core API with a key the two services share. The enqueue endpoint ignores the request body. Each transaction sets `app.tenant_id` from that token, so a job that names the wrong tenant for a document finds no row and changes nothing.

Stored files live under keys built only from server-generated IDs (`tenants/<tenant>/documents/<document>`). The bucket is private and nothing issues public URLs.

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

### Assistant and widget — `AssistantAndWidgetIntegrationTest`, `OriginsTest`

Reading, updating, listing versions, rotating the key and replacing origins in another tenant all return 404 and change nothing. Origin matching is tested to be exact: look-alike, sub-domain, suffix, scheme and port variants do not match.

### Knowledge — `KnowledgeDocumentIntegrationTest` (core API)

| Resource | Attack | Expected |
|---|---|---|
| Documents | List or upload in the other tenant's workspace | 404 |
| Documents | Read, archive, reprocess, delete or replace another tenant's document, directly and by ID substitution through the caller's own workspace | 404; document unchanged |
| Chunks (worker role) | Read, update or delete another tenant's documents and chunks; insert a chunk into another tenant | Zero rows, or rejected by RLS |
| Chunks (schema) | Insert a chunk with the caller's tenant ID pointing at another tenant's document | Rejected by the composite foreign key |
| Worker role | Read any table outside knowledge; change a non-processing column; delete a document | Permission denied |
| Outbox | Read without context; update with only the dispatcher setting | Zero rows |

### Knowledge — `test_pipeline.py` (document worker)

Runs the real pipeline against PostgreSQL with the real migrations, connected as `servora_worker`.

- A job naming tenant A with tenant B's document ID is skipped and cannot mark the document failed.
- With tenant A's context the worker sees A's chunk text and none of B's; with no context it sees nothing.
- Archived, deleted, ready and failed documents are left alone.
- A document deleted or archived while it is being embedded gets no chunks.
- Processing twice, or after a simulated crash, leaves exactly one set of chunks.
- A new version retires the previous one in the same transaction; a failed new version leaves the previous one answering.

### Role tests — `RoleEnforcementIntegrationTest`, `PermissionTest`

The role matrix is asserted independently of its implementation, and enforced over HTTP for all four roles, including that an admin cannot change an owner or grant ownership.

## Results

Recorded on 2026-11-16, local runs: core API 112 tests, document worker 50 tests, dashboard 40 tests, all passing. CI results are on the repository's Actions page.

One manual end-to-end run on the same date uploaded different documents to two workspaces through the real API, storage emulator, outbox, worker and embedding model. Each tenant's chunks were stored under its own `tenant_id`, and a deliberately broken PDF failed with `PARSE_ERROR`.

## Known gaps

- No test yet races two owners demoting each other; the last-owner rule relies on a row lock that is reviewed but not exercised concurrently.
- Global identity tables are outside RLS, as described above.
- A valid access token keeps working for up to 15 minutes after a user is disabled. Membership changes take effect immediately.
- Retrieval does not exist yet. The rule that only active chunks of the caller's tenant are returned is enforced by the schema and policies now, and is tested end to end in M3.
- The storage emulator's bucket is shared by all tenants and separated by key prefix. Isolation there rests on the backend being the only client; there are no per-tenant storage credentials.
