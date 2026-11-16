# ADR-002: Shared schema multi-tenancy with tenant_id, RLS and tenant-aware foreign keys

- **Status:** Accepted
- **Date:** 2026-11-16

## Context

Tenants must never see each other's data. Database-per-tenant or schema-per-tenant adds operational cost that an initial SaaS does not need.

## Decision

One PostgreSQL database and one schema. Every tenant-owned table carries `tenant_id UUID NOT NULL`. Row-Level Security is enabled and forced on those tables with a policy on the transaction-local setting `app.tenant_id`. Parent tables expose `UNIQUE (tenant_id, id)` and children reference `(tenant_id, parent_id)`. The runtime role does not own the tables and has no `BYPASSRLS`.

## Consequences

- Application-level scoping is the primary control; RLS is defence in depth if a query misses its predicate.
- Tenant context must be set inside every transaction after membership is validated, and must be proven not to leak across pooled connections.
- Every new table needs a policy, a composite key and a cross-tenant negative test.

## Revisit when

Revisit if a tenant requires physical isolation or data residency.
