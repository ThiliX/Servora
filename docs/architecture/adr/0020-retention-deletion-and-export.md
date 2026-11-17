# ADR-020: Conversation retention, deletion and workspace export

- **Status:** Accepted (decided in M6)
- **Date:** 2026-11-17

## Context

FR-042 and the handbook's privacy baseline (section 13.2) ask for a retention and deletion approach and a path to export tenant data. Until now the runtime role had no `DELETE` privilege on any tenant table: deletion was denied everywhere by design.

## Decision

**One narrow way to delete.** A database function, `delete_conversation_data(conversation_id)`, deletes one conversation and everything that belongs to it: messages and their citations, the AI interactions behind them, the ticket and its events, internal notes, the rating, notifications, and the customer record if it has no other conversation. It runs with its owner's rights (`SECURITY DEFINER`, fixed `search_path`), acts only on the workspace bound in the transaction (it refuses to run with none), and names the tenant in every statement. The runtime role may execute it; it still has no `DELETE` privilege on the tables themselves. Usage events and audit events are kept.

**Playground questions (amended in M7).** Questions asked in the playground are stored with no conversation, so deleting conversations never removed them. A second function, `delete_expired_playground_interactions(limit)`, deletes a workspace's playground records older than that workspace's retention period. It reads the period from the tenant row itself, so the caller cannot choose a cutoff, and it acts only on the bound workspace. The retention job calls it after deleting conversations.

**Who deletes.** Owners only (`MANAGE_DATA`): one conversation at a time from the conversation view, for example on a customer's erasure request; audited as `CONVERSATION_DELETED`.

**Retention.** Owners set `conversation_retention_days` between 30 and 3650, or leave it empty to keep conversations. A scheduled job, hourly by default, deletes conversations whose last activity is older than that, up to 200 per workspace per run. A conversation whose ticket is still open, in progress or waiting is kept until the ticket is resolved. The job lists workspaces under its own transaction-local flag (`app.retention_job`), which exposes only workspaces that have a retention period, and binds each workspace before touching its data. Each run that deletes anything writes `CONVERSATIONS_PURGED` with the count to the workspace's audit log, with no actor.

**Export.** Owners download one JSON document: workspace settings, plan, members (name, email, role), invitations (no token hashes), assistant configuration, widgets and allowed origins, document metadata (not the files), up to 5,000 most recent conversations with their public messages, tickets and their history, internal notes, ratings, the audit log and monthly usage. It is limited to five exports per workspace per hour and audited as `WORKSPACE_EXPORTED`. If there are more conversations, the export says it was truncated.

**Not in scope.** Deleting a whole workspace, deleting stored document files on retention, and a per-customer search for erasure. Deleting a workspace is an operator procedure until a later milestone.

## Consequences

- Deletion cannot be undone, and there is no soft-delete period.
- A large workspace may need several hourly runs to catch up after retention is first set.
- The export is built in memory, which is why it is capped.
