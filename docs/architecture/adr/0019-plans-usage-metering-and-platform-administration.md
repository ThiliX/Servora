# ADR-019: Plans, usage metering and platform administration

- **Status:** Accepted (decided in M6)
- **Date:** 2026-11-17

## Context

FR-038, FR-039 and FR-043 ask for AI usage and estimated cost to be recorded, for plan limits that are enforced, and for platform operators to list workspaces and suspend or reactivate them. There is no payment provider in the MVP. Platform access is a separate permission domain from workspace roles (handbook section 2.2) and must not become routine access to customer conversations.

## Decision

**Plans and subscriptions.** `plans` is a global catalogue (Free, Starter, Growth) with monthly limits on AI requests, tokens and new conversations, and a cap on documents held. Each workspace has one `subscriptions` row, which replaces `tenants.plan_code`. New workspaces start on Free. A platform operator changes the plan; there is no self-service upgrade.

**Usage events.** `usage_events` is append-only: one row per AI request (source, provider, model, input and output tokens, estimated cost), per uploaded document and per started conversation. Events are written in the same transaction as the work they measure: the AI request in the same transaction as its `ai_interactions` row. No event holds message or document content, and there is no foreign key to what was measured, so usage history survives deletion. A request refused because of a limit, or never sent because the AI service was unreachable, is not metered.

**Estimated cost.** Configured as dollars per million input and output tokens for the one active provider (`SERVORA_USAGE_INPUT_PRICE_PER_MTOK_USD`, `SERVORA_USAGE_OUTPUT_PRICE_PER_MTOK_USD`). Cost is stored in millionths of a dollar. With no price configured the cost is reported as unknown, never as zero. It is an estimate, not a bill.

**Limits.** Counted over the calendar month in UTC. Checked before the work starts:

- Widget: over the AI request or token limit, the model is not asked. The reply is the assistant's fallback message with outcome `OVER_LIMIT`, which the escalation policy treats like a service error, so the customer is handed to a person. Over the conversation limit, a new chat is refused with `403 PLAN_LIMIT_REACHED` and a message that does not mention plans; the widget shows "not available right now".
- Playground and agent suggestions: `403 PLAN_LIMIT_REACHED`.
- Uploads: `403 PLAN_LIMIT_REACHED` when the workspace holds its plan's number of documents. The global `max-documents-per-workspace` stays as a ceiling.

Checking and counting are separate steps, so requests that run at the same moment can pass the check together. The overshoot is bounded by concurrency and accepted for the MVP. The existing per-day Redis limit on AI replies stays as an abuse guard.

**Platform administration.** A platform admin is a row in `platform_admins`, added only by an operator with database access (see the runbook in the README); the runtime role can read that table but not write it. Platform endpoints live under `/api/v1/platform` and return `404` to everyone else. A platform request binds the user and then sets a transaction-local flag, `app.platform_admin`. The cross-tenant policies on `tenants`, `subscriptions` and `usage_events` require **both** the flag and a `platform_admins` row for the bound user, so the flag alone grants nothing. No platform policy exists on conversations, messages, documents, members or notes. Every platform change is written to `platform_audit_events` and to the workspace's own `audit_events`, so the workspace's owners see what an operator did.

**Suspension.** A suspended workspace is refused at the dashboard (`403 WORKSPACE_SUSPENDED`), at the widget configuration and session endpoints (not found), and, new in M6, on every call made with an existing widget session token (`403 WIDGET_UNAVAILABLE`). Members still see the workspace in their list, marked suspended with the operator's reason.

## Consequences

- Plan limits are soft by up to the number of concurrent requests.
- Changing plan needs an operator; there is no billing integration.
- A platform operator can change any column of `tenants` under the update policy; the application only changes the status, reason and plan, and every change is audited.
- Listing workspaces is not itself audited; changes are.
