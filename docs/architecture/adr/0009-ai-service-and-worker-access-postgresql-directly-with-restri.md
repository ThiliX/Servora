# ADR-009: AI service and worker access PostgreSQL directly with restricted roles under RLS

- **Status:** Accepted (recommended in M0)
- **Date:** 2026-11-16

## Context

The AI service must read chunks for retrieval and the worker must write them. Routing every vector query through the core API would add latency and a large internal API. Direct access is only acceptable if it cannot bypass tenant isolation.

## Decision

Use dedicated database roles. `servora_ai` has `SELECT` on the knowledge tables needed for retrieval and nothing else. `servora_worker` may insert chunks and update document processing state. Neither owns tables nor has `BYPASSRLS`. Both set `app.tenant_id` transaction-locally from the tenant in the signed internal token issued by the core API, never from request bodies or model output.

## Consequences

- Retrieval is a single SQL query under the same RLS policies as the core API.
- A compromised AI service can read knowledge for a tenant it holds a valid token for, but cannot read conversations, tickets or users.
- Cross-tenant tests must run against these roles, not only against the core API role.

## Revisit when

Revisit if the internal token model proves hard to secure; the fallback is retrieval through an authenticated core API endpoint.
