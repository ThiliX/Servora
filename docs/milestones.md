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
- [x] LLM provider chosen (ADR-013): Anthropic Claude, 2026-11-17

## M1 — Identity and tenant foundation

Requirements: FR-001, FR-002, FR-003, FR-004, FR-005, FR-006, FR-007. Starts FR-041 and FR-044.

- [x] Core API scaffold, error envelope, correlation IDs, OpenAPI
- [x] Migrations, database roles and RLS for identity and tenant tables
- [x] Register, verify, login, refresh rotation, logout
- [x] Tenant context resolution and role enforcement
- [x] Workspaces, memberships, invitations
- [x] Dashboard: auth, workspace create and switch, team, settings
- [x] Cross-tenant, pooled-connection and RLS tests passing (56 backend tests, 28 dashboard tests, local run 2026-11-16)
- [x] Owner and agent flows checked by hand in a browser against the real API and database
- [x] CI green on GitHub for the M1 branch (2026-11-16, pull request 1)
- [x] Merged to `main` and tagged `v0.1-m1` (2026-11-16)

Deferred from M1, with the milestone that picks each up:

- Email delivery through a real provider (links are logged in development) — M7
- Shared-store login rate limiting (in memory today) — M7 (the other limits moved to Redis in M4)
- Application containers in Compose — M7
- Playwright end-to-end suite — M4 onward

## M2 — Assistant, widget configuration, knowledge ingestion

Requirements: FR-008, FR-009, FR-011 to FR-019.

- [x] Assistant configuration with immutable versions
- [x] Widget key and allowed origins
- [x] Private storage abstraction and validated upload
- [x] Outbox dispatch and Celery worker pipeline
- [x] Document lifecycle: list, archive, delete, reprocess, replace
- [x] Dashboard: assistant, widget and knowledge pages
- [x] Ingestion, idempotency and cross-tenant tests passing (112 core API, 50 worker, 40 dashboard; local run 2026-11-16)
- [x] Real upload run end to end through the local stack (storage, outbox, worker, real embedding model)
- [x] CI green on GitHub for the M2 branch (2026-11-16, pull request 2)
- [x] Merged to `main` and tagged `v0.2-m2` (2026-11-17)

Deferred from M2, with the milestone that picks each up:

- Malware scanning of uploads — M7
- Automated test of the S3 storage implementation and the HTTP enqueue client (exercised end to end by hand today) — M7
- Chunk size and overlap evaluated against a labelled question set — M3
- Scanned PDFs without a text layer (no OCR) — not planned for the MVP

## M3 — RAG AI service

Requirements: FR-010, FR-025 to FR-029, FR-031.

- [x] FastAPI service with internal authentication
- [x] Tenant-filtered retrieval and evidence gate, through the read-only `servora_ai` role
- [x] Provider adapter, Anthropic Claude provider and fake provider
- [x] Structured output and citation verification, in the AI service and again in the core API
- [x] Admin playground: endpoint and dashboard page
- [x] Interactions recorded with model, prompt version, latency, tokens, retrieval scores and outcome
- [x] Evaluation dataset and recorded baseline ([docs/ai/evaluation-baseline.md](ai/evaluation-baseline.md))
- [x] AI test cases passing on the fake provider (121 core API, 45 AI service, 50 worker, 46 dashboard; local run 2026-11-17)
- [x] Real run end to end through the local stack with the real embedding model and the fake provider
- [ ] One answer from the live Anthropic API checked by hand (needs an API key; not done)
- [x] CI green on GitHub for the M3 branch (2026-11-17, pull request 3)
- [x] Merged to `main` and tagged `v0.3-m3` (2026-11-17)

Deferred from M3, with the milestone that picks each up:

- Answer quality evaluation with the real model, and injection tests against it — M7
- Chunk size and overlap comparison — M7
- Shared-store rate limiting for the playground — done in M4
- Sentiment detection (FR-030) — after the MVP

## M4 — Public chat widget and conversations

Requirements: FR-020 to FR-024.

- [x] Public widget endpoints and scoped session tokens ([ADR-017](architecture/adr/0017-public-widget-access-and-session-tokens.md))
- [x] Conversation and message persistence with citations
- [x] Rate limits in Redis and message idempotency
- [x] Pre-delivery state re-check and SSE streaming ([ADR-014](architecture/adr/0014-server-sent-events-from-the-core-api-for-customer-streaming.md), amended: final message only)
- [x] Loader, iframe widget and two demo sites
- [x] Dashboard conversation list and read-only thread view
- [x] Token-scope, origin, isolation and idempotency tests passing (137 core API, 13 widget, 49 dashboard, 45 AI service, 50 worker; local run 2026-11-17)
- [x] Both demo sites checked by hand in a browser against the real stack: answers with citations from their own documents, TechStore abstains on a hotel question, handoff stops AI replies
- [ ] Playwright end-to-end suite (not built; see deferrals)
- [ ] CI green on GitHub for the M4 branch
- [ ] Reviewed and merged to `main`

Deferred from M4, with the milestone that picks each up:

- Playwright end-to-end suite for the widget and dashboard — M7
- Earlier turns of the conversation sent to the AI service as context (each question is answered on its own today) — M5
- Resuming a conversation after the session token expires, or across devices — after the MVP
- Token-by-token streaming of answers (see ADR-014) — revisit when real-model latency is measured

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
