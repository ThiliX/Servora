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
- [x] CI green on GitHub for the M4 branch (2026-11-17, pull request 4)
- [x] Merged to `main` (2026-11-17)

Deferred from M4, with the milestone that picks each up:

- Playwright end-to-end suite for the widget and dashboard — M7
- Earlier turns of the conversation sent to the AI service as context (each question is answered on its own today) — M7
- Resuming a conversation after the session token expires, or across devices — after the MVP
- Token-by-token streaming of answers (see ADR-014) — revisit when real-model latency is measured

## M5 — Tickets and human agent workspace

Requirements: FR-032 to FR-037, FR-040.

- [x] Escalation policy and idempotent ticket creation ([ADR-018](architecture/adr/0018-human-handoff-escalation-and-takeover.md))
- [x] Assignment and ticket status workflow
- [x] Atomic takeover and AI reply suppression
- [x] Agent replies, internal notes, AI suggestions, notifications
- [x] Resolution and customer rating, in the API and the widget
- [x] Agent workspace UI (queue, thread, ticket panes) and notifications in the dashboard
- [x] Concurrency, idempotency and visibility tests passing (151 core API, 16 widget, 55 dashboard; local run 2026-11-17). The takeover race test ran four times, 24 rounds each, with both orders observed every time
- [x] Handbook journey 4.3 checked by hand in a browser against the real stack: handoff, takeover, agent reply reaching the widget, resolution, rating shown to the team
- [ ] Playwright end-to-end suite (deferred to M7)
- [x] CI green on GitHub for the M5 branch (pull request 5)
- [x] Reviewed and merged to `main` (2026-11-17)

Deferred from M5, with the milestone that picks each up:

- Playwright test of the full handoff — M7, with the other browser tests
- Workspace-configurable escalation rules, and risk terms in other languages — after the MVP
- Instant delivery of agent replies (push instead of five-second polling) — after the MVP
- Email notifications (in-app only today) — M7, with email delivery

## M6 — Analytics, metering, platform admin

Requirements: FR-038, FR-039, FR-041, FR-042, FR-043 (post-MVP).

- [x] Plans, subscriptions and append-only usage events; AI requests metered with tokens and estimated cost ([ADR-019](architecture/adr/0019-plans-usage-metering-and-platform-administration.md))
- [x] Plan limits enforced: the widget offers a person when AI is over the limit, new chats are refused politely over the conversation limit, and the playground, suggestions and uploads return `PLAN_LIMIT_REACHED`
- [x] Analytics endpoint and Overview page: conversations resolved by AI, escalated and unanswered, daily trend, ticket queue, response times, intents, escalation reasons, outcomes, ratings
- [x] Usage and plan page
- [x] Audit coverage extended to ticket assignment, takeover, resolution and closure, retention, deletion and export; audit log page for owners and admins
- [x] Retention period, owner deletion of a conversation, and workspace export ([ADR-020](architecture/adr/0020-retention-deletion-and-export.md))
- [x] Platform admin: list workspaces, suspend, reactivate, change plan, with a platform audit log; suspension also stops existing widget sessions
- [x] Tests passing (169 core API, 66 dashboard; local run 2026-11-17), including cross-tenant checks for usage, analytics, audit, deletion and export, and suspension at dashboard and widget
- [x] CI green on GitHub for the M6 branch (pull request 7)
- [x] Reviewed and merged to `main` (2026-11-17), tagged `v0.6-m6`

Deferred from M6, with the milestone that picks each up:

- Deleting a whole workspace, and deleting stored document files on retention — after the MVP (an operator procedure until then)
- Self-service plan changes and billing — after the MVP
- Analytics in the workspace's own timezone (UTC today) — after the MVP
- Browser tests of the Overview, Usage and Platform pages — M7, with the other Playwright tests

## M7 — Hardening and release

Requirements: FR-044, FR-045, non-functional targets.

M7 is delivered in four phases, each its own pull request.

**Phase 1 — security hardening**

- [x] Review against the OWASP API and LLM top-10 lists with test evidence ([owasp-review.md](security/owasp-review.md)); threat model updated
- [x] Cross-tenant ID-substitution suite for every tenant-scoped endpoint (`CrossTenantMatrixIntegrationTest`)
- [x] Security response headers on every API response
- [x] Login limits in Redis, per account and address, per account, and per address; sign-up limits per address and mailbox
- [x] Log review: unexpected errors logged without messages; playground questions covered by retention
- [x] Secret scanning (gitleaks) and dependency scanning (Trivy, pip-audit, npm audit) in CI; vulnerable `setuptools` fixed in both Python images
- [x] Tests passing (181 core API, 66 dashboard, 16 widget; local run 2026-11-17)
- [x] CI green and merged (pull request 8)

**Phase 2 — browser and load tests**

- [x] Playwright suite for the MVP demonstration script against the real stack, including the M6 pages ([tests/e2e](../tests/e2e/README.md)); 11 of 11 steps passed in two consecutive local runs
- [x] Load test of the non-AI endpoints with recorded machine and workload ([load-test.md](testing/load-test.md)): P95 11.6 ms at 20 virtual users and 15.0 ms at 100, target 500 ms
- [x] Browser suite in CI (added in phase 3a)
- [x] CI green and merged (pull request 9)

**Phase 3a — production profile**

- [x] Core API, dashboard and widget in containers; migrations in a one-shot container, so the API never holds the owner's password ([ADR-021](architecture/adr/0021-production-deployment-profile.md))
- [x] Production Compose overlay: only the gateway and widget host publish ports, internal data network, non-root read-only containers with limits ([production.md](operations/production.md))
- [x] Image scanning in CI; every image at 0 fixable high or critical vulnerabilities after base-image updates and removing `pip` from the Python images
- [x] Browser suite in CI against the production stack
- [x] Embed snippet fixed: the dashboard shows the real loader URL, and the widget host forwards the public widget API
- [x] Backup and restore scripts, and a drill performed with identical counts, policies, grants and file checksums ([backup-and-restore.md](operations/backup-and-restore.md))
- [x] CI green and merged (pull request 10)

**Phase 3b — observability**

- [x] One ID per request across services: the request ID is the OpenTelemetry trace ID, continued by the AI service and the worker, and across the outbox into the ingestion job ([ADR-022](architecture/adr/0022-observability.md))
- [x] Metrics in the Prometheus format on internal ports only: HTTP, AI outcomes, latency, tokens, retrieval, plan and rate limits, outbox, queue depth and wait, document processing; no tenant labels ([observability.md](operations/observability.md))
- [x] JSON log lines with trace, span and workspace IDs in the production profile
- [x] Optional Prometheus and Jaeger overlay, with alert rules
- [x] Observability checks in the CI job "Production stack"
- [x] nginx 1.30.7 in the dashboard and widget images, for six advisories published 2026-11-18 (version matrix)
- [x] Tests passing (190 core API, 58 AI service, 53 worker; browser suite 11 of 11 and observability checks against the production profile; local run 2026-11-21)
- [ ] CI green and merged

**Remaining phases**

- [ ] Phase 4: AI evaluation report, seed script, documentation and demo setup

Original checklist, ticked as phases complete:

- [x] Security review with test evidence (phase 1)
- [x] End-to-end suite for the MVP demonstration script (phase 2, local; CI in phase 3)
- [x] Load test with recorded conditions (phase 2)
- [ ] AI evaluation report
- [x] Compose production profile and CI for all stacks (phase 3a)
- [x] Observability: logs, metrics, traces (phase 3b)
- [x] Backup and restore drill performed (phase 3a)
- [ ] Documentation, seed script and demo setup

## Post-MVP

- [ ] FR-030 sentiment classification
