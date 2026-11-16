# Milestone checklist

Tasks, tests and exit gates for each milestone are in the [implementation plan](implementation-plan.md). This file tracks status. Tick an item only when it is done and verified.

## M0 — Repository and architecture baseline

- [x] Environment and repository inspected
- [x] Versions verified and recorded in the [version matrix](architecture/version-matrix.md)
- [x] ADR-001 to ADR-014 written ([index](architecture/adr/README.md))
- [x] Repository skeleton, README, conventions, `.env.example`, `.gitignore`
- [x] Architecture, database and threat-model drafts
- [x] Compose file with PostgreSQL (pgvector) and Redis, with health checks
- [x] CI workflow written
- [x] Compose stack verified healthy and `CREATE EXTENSION vector` succeeds (2026-11-16: PostgreSQL 18.7, pgvector 0.8.7, Redis 8.8.4)
- [x] First commit pushed and CI green on GitHub (2026-11-16, commit 4c66556)
- [ ] Branch protection enabled on `main`
- [ ] LLM provider chosen (ADR-013) — required before M3, not before M1

## M1 — Identity and tenant foundation

Requirements: FR-001, FR-002, FR-003, FR-004, FR-005, FR-006, FR-007. Starts FR-041 and FR-044.

- [ ] Core API scaffold, error envelope, correlation IDs, OpenAPI
- [ ] Migrations, database roles and RLS for identity and tenant tables
- [ ] Register, verify, login, refresh rotation, logout
- [ ] Tenant context resolution and role enforcement
- [ ] Workspaces, memberships, invitations
- [ ] Dashboard: auth, workspace create and switch, team, settings
- [ ] Cross-tenant, pooled-connection and RLS tests passing

## M2 — Assistant, widget configuration, knowledge ingestion

Requirements: FR-008, FR-009, FR-011 to FR-019.

- [ ] Assistant configuration with immutable versions
- [ ] Widget key and allowed origins
- [ ] Private storage abstraction and validated upload
- [ ] Outbox dispatch and Celery worker pipeline
- [ ] Document lifecycle: list, archive, delete, reprocess
- [ ] Dashboard: assistant, widget and knowledge pages
- [ ] Ingestion, idempotency and cross-tenant tests passing

## M3 — RAG AI service

Requirements: FR-010, FR-025 to FR-029, FR-031.

- [ ] FastAPI service with internal authentication
- [ ] Tenant-filtered retrieval and evidence gate
- [ ] Provider adapter, real provider and fake provider
- [ ] Structured output and citation verification
- [ ] Admin playground
- [ ] Evaluation dataset and recorded baseline
- [ ] AI test cases passing on the fake provider

## M4 — Public chat widget and conversations

Requirements: FR-020 to FR-024.

- [ ] Public widget endpoints and scoped session tokens
- [ ] Conversation and message persistence with citations
- [ ] Rate limits and message idempotency
- [ ] Pre-delivery state re-check and SSE streaming
- [ ] Loader, iframe widget and two demo sites
- [ ] Token-scope, origin, isolation and end-to-end tests passing

## M5 — Tickets and human agent workspace

Requirements: FR-032 to FR-037, FR-040.

- [ ] Escalation policy and idempotent ticket creation
- [ ] Assignment and ticket status workflow
- [ ] Atomic takeover and AI reply suppression
- [ ] Agent replies, internal notes, AI suggestions, notifications
- [ ] Resolution and customer rating
- [ ] Agent workspace UI
- [ ] Concurrency, idempotency and visibility tests passing

## M6 — Analytics, metering, platform admin

Requirements: FR-038, FR-039, FR-041, FR-042, FR-043 (post-MVP).

- [ ] Usage events and plan limits
- [ ] Analytics endpoints and dashboard pages
- [ ] Audit coverage and audit log view
- [ ] Scoped tenant export and deletion
- [ ] Platform admin: list, suspend, reactivate tenants

## M7 — Hardening and release

Requirements: FR-044, FR-045, non-functional targets.

- [ ] Security review with test evidence
- [ ] AI evaluation report
- [ ] End-to-end suite for the MVP demonstration script
- [ ] Load test with recorded conditions
- [ ] Compose production profile and CI for all stacks
- [ ] Observability: logs, metrics, traces
- [ ] Backup and restore drill performed
- [ ] Documentation, seed script and demo setup

## Post-MVP

- [ ] FR-030 sentiment classification
