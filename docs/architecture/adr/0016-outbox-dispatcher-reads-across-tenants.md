# ADR-016: The outbox dispatcher reads pending events across tenants

- **Status:** Accepted (decided in M2)
- **Date:** 2026-11-16

## Context

ADR-010 delivers work to the document worker through an outbox table. `outbox_events` is tenant-owned and under forced Row-Level Security like every other tenant table. The dispatcher, however, is a background job: it runs outside any request, has no user and no tenant, and must find pending events for all tenants.

## Decision

`outbox_events` has a `SELECT` policy that passes for the current tenant **or** when the transaction-local setting `app.outbox_dispatcher` is `on`. Only `TenantContext.enableOutboxDispatch()` sets it, and only `OutboxProcessor` calls that.

The setting unlocks reading `outbox_events` and nothing else. No other table's policy mentions it, and the `UPDATE` policy on `outbox_events` still requires `app.tenant_id`. The dispatcher therefore works in two steps inside one transaction: it finds a pending event with the dispatcher setting, then binds that event's own tenant, taken from the server-written row, before locking the row or changing anything.

## Consequences

- The runtime role still cannot bypass RLS, and there is still no `SECURITY DEFINER` function.
- Outbox payloads must stay free of secrets and customer content, because the dispatcher path can read them across tenants. Today they are empty; the event names a document ID only.
- A test asserts that with the dispatcher setting on, the application role can read pending events of several tenants but can update none and can see no documents or memberships.
- Events are claimed with `FOR UPDATE SKIP LOCKED`, so several API instances can run the dispatcher.

## Revisit when

A second kind of cross-tenant background job appears. At that point a dedicated database role for background work is cleaner than a growing set of settings.
