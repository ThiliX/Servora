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

Policies read these transaction-local settings. All are set only by `TenantContext`.

| Setting | Set when | What it unlocks |
|---|---|---|
| `app.tenant_id` | After membership and permission are validated; for a tenant ID the server just generated; or for the tenant of an invitation whose token was presented and checked | Rows of that tenant |
| `app.user_id` | For every authenticated request | The user's own `memberships` rows, and `tenants` rows where they are an active member |
| `app.invitation_token_hash` | When a user presents an invitation token | The single `invitations` row with that token hash |
| `app.outbox_dispatcher` | By the outbox dispatcher only | Reading `outbox_events` of any tenant. No writes, no other table |
| `app.widget_public_key` | For a public widget request presenting a key | The single `widgets` row with that key ([ADR-017](../architecture/adr/0017-public-widget-access-and-session-tokens.md)) |
| `app.platform_admin` | By the platform admin service, after binding the user | Only together with a `platform_admins` row for `app.user_id`: reading and updating `tenants` and `subscriptions`, and reading `usage_events`, of any tenant. Nothing else ([ADR-019](../architecture/adr/0019-plans-usage-metering-and-platform-administration.md)) |
| `app.retention_job` | By the retention job only | Reading the `tenants` rows that have a retention period. The job binds `app.tenant_id` before reading or deleting any workspace data |

The settings other than `app.tenant_id` exist because some work happens before, or outside, a tenant context. They are narrow by construction and covered by the tests below. See [ADR-015](../architecture/adr/0015-row-level-security-context-beyond-the-tenant-id.md) and [ADR-016](../architecture/adr/0016-outbox-dispatcher-reads-across-tenants.md).

## The document worker

The worker is a separate Python service with its own database role, `servora_worker`. It is unprivileged in the same way as the application role and is granted only what ingestion needs: it can read documents, update a document's processing columns, and write chunks. It cannot read users, memberships, tenants, invitations, audit events, the outbox, assistants or widgets.

The worker learns which tenant and document to process from a short-lived token signed by the core API with a key the two services share. The enqueue endpoint ignores the request body. Each transaction sets `app.tenant_id` from that token, so a job that names the wrong tenant for a document finds no row and changes nothing.

Stored files live under keys built only from server-generated IDs (`tenants/<tenant>/documents/<document>`). The bucket is private and nothing issues public URLs.

## The AI service

The AI service is a separate Python service with its own database role, `servora_ai`, which is read-only: it can select chunks and four columns of `documents`, and nothing else. It cannot read users, assistants, interactions or the storage key of a document, and it cannot write.

It learns the tenant from a short-lived token signed by the core API (audience `servora-ai-service`); the request body has no tenant field. Each retrieval transaction sets `app.tenant_id` from that token and also filters by tenant in SQL.

The core API does not trust the AI service's reply. Before returning or storing an answer it checks, under Row-Level Security in the caller's tenant, that every chunk the reply names exists in that tenant, and that every citation is one of the chunks the reply says were given to the model. A reply that fails is discarded and the fallback message is returned instead.

## The public widget

Public requests have no user and start with no tenant. A widget key is resolved in a transaction that may read only the widget with that key (`app.widget_public_key`); the widget's tenant is then bound from that row. After a session starts, the tenant, customer and conversation come from the session token alone, which is signed with a key separate from the dashboard's. The public endpoints accept no tenant, workspace or conversation id in the request. See [ADR-017](../architecture/adr/0017-public-widget-access-and-session-tokens.md).

An AI reply goes through the same verification as the playground: every chunk it names must exist in the conversation's tenant, and every citation must be one the AI service says it gave the model.

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

### AI retrieval — `test_pipeline.py`, `test_api_and_provider.py` (AI service)

- Two tenants with near-identical documents are each answered only from their own; the other tenant's chunk never appears in retrieval.
- With no tenant set the AI role sees no chunks; with another tenant set it cannot see a known chunk id.
- The AI role cannot update or delete chunks, read `storage_key`, or read users or interactions.
- A token for one tenant with another tenant's id in the request body still retrieves only the token's tenant.
- Inactive chunks, chunks of archived documents and chunks from another embedding profile are never retrieved.

### Playground — `PlaygroundIntegrationTest` (core API)

- A reply citing another tenant's chunk is rejected, and nothing from it reaches the response.
- Citations that were not given to the model, or that do not exist, are rejected.
- Agents get `403`; members of another workspace get `404`; the AI service is not called in either case.
- `ai_interactions` rows are invisible without a tenant and from another tenant, cannot be inserted for another tenant, and cannot be updated by the application role.

### Widget and conversations — `WidgetConversationIntegrationTest` (core API)

- Origins: exact match passes; look-alike, suffix, sibling-subdomain, scheme, port, `null`, missing and other-tenant origins are refused, with no CORS header.
- Keys: unknown, malformed, rotated and disabled keys give the same `404`.
- Tokens: a token reads only its own conversation; tampered, re-signed with another key, unsigned (`alg: none`), expired and dashboard tokens are rejected; a widget token is rejected by every dashboard endpoint tried.
- A TechStore question answered by a faulty AI service with a HotelPro chunk returns the fallback, and no HotelPro identifier, text or citation appears in the response or the database.
- Dashboard: agents get `403`, another workspace's owner gets `404`, and a conversation id from another workspace is not found.

### Tickets and the agent workspace — `TicketWorkflowIntegrationTest`, `TicketRulesTest` (core API)

- Another workspace's owner gets `404` for tickets, ticket lists, notes, takeover, resolution and notifications, through either workspace's path.
- An agent cannot see a ticket assigned to another agent (tickets, notes, messages, conversation), cannot assign, and cannot take over a conversation that was never escalated.
- Internal notes and unsent AI suggestions never appear in the public history or in any message stream.
- Parallel escalations create exactly one ticket, one notice and one creation event.
- An AI reply is never delivered after a takeover: the race runs 24 rounds per test run with both orders observed.

### Usage, analytics, data and platform — `UsageAnalyticsAndAdminIntegrationTest` (core API)

- Another workspace's owner gets `404` for usage, analytics, the audit log, export and conversation deletion; each workspace's numbers count only its own activity.
- `delete_conversation_data` refuses to run with no workspace bound, and with another workspace bound it finds nothing to delete.
- Setting `app.platform_admin` without a `platform_admins` row exposes no other workspace's `tenants` or `usage_events` rows.
- Platform endpoints return `404` to workspace owners. A suspended workspace is refused at the dashboard, the widget configuration and existing widget sessions, and the other workspace is unaffected. Each platform change appears in the platform log and in that workspace's audit log only.
- The retention job deletes only expired conversations of the workspace that set a period, keeps conversations with an active ticket, and keeps usage events.

### Role tests — `RoleEnforcementIntegrationTest`, `PermissionTest`

The role matrix is asserted independently of its implementation, and enforced over HTTP for all four roles, including that an admin cannot change an owner or grant ownership.

## Results

Recorded on 2026-11-17, local runs: core API 169 tests (after M6), AI service 45 tests, document worker 50 tests, dashboard 66 tests (after M6), widget 16 tests, all passing. CI results are on the repository's Actions page.

Manual end-to-end runs on 2026-11-17 against the real API, worker, AI service and embedding model, with the fake LLM provider:

- Two workspaces with different documents, asked the same question through the playground: each answered from its own documents only.
- The two demo websites on separate origins, in a browser: each widget answered from its own workspace's documents with citations. Asked about hotel check-in, the TechStore widget abstained; its retrieval contained no HotelPro chunk, and no HotelPro identifier appeared in the TechStore conversation's stored data or the API's reply log lines. The TechStore key used on the HotelPro site showed no widget.

An earlier run on 2026-11-16 covered ingestion, including a broken PDF failing with `PARSE_ERROR`.

## Known gaps

- No test yet races two owners demoting each other; the last-owner rule relies on a row lock that is reviewed but not exercised concurrently.
- Global identity tables are outside RLS, as described above.
- A valid access token keeps working for up to 15 minutes after a user is disabled. Membership changes take effect immediately.
- The core API's HTTP client for the AI service is exercised by the manual end-to-end run only; automated tests replace it with a scripted stand-in.
- The storage emulator's bucket is shared by all tenants and separated by key prefix. Isolation there rests on the backend being the only client; there are no per-tenant storage credentials.
