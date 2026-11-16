# Servora — Full Implementation Plan

**Product:** Servora, a multi-tenant AI customer-support SaaS (named "SupportPilot" in the source handbook; every identifier below uses the new name).
**Repository:** <https://github.com/ThiliX/Servora.git>
**Sources:** `SupportPilot_Implementation_Handbook.pdf` (product, architecture, FR-001 to FR-045) and `SupportPilot_Master_Implementation_Prompt.md` (governing workflow and rules; a renamed copy is in `Servora_Master_Implementation_Prompt.md`).
**Plan date:** 2026-11-16

---

## 1. Starting point

| Item | Finding (checked 2026-11-16) | Consequence |
|---|---|---|
| GitHub repo | Reachable, no branches or commits yet | M0 creates the first commit; nothing to migrate |
| Local folder | `Downloads\supportpilot`, not a git repo, holds only the two reference files | M0 clones/initialises a `servora` working copy |
| Java | 25.0.1 LTS | Matches the handbook's Java 25 target |
| Maven | Not installed | Use the Maven Wrapper (`mvnw`) committed in the repo |
| Node / npm | 24.13.0 / 11.6.2 | Fine for Vite, Vitest, Playwright |
| Python | 3.11.4 on host | Services run in Docker with a pinned Python image; host version is not binding |
| Docker / Compose | 29.5.3 / v5.1.4 | Fine |
| PostgreSQL client | Not installed | Use the pgvector Postgres container and `docker compose exec` for `psql` |

Exact framework versions (Spring Boot, pgvector image, FastAPI, Celery, React, Tailwind) are **not fixed in this plan**. Both source documents require verifying them against official documentation, so pinning them is the first task of M0 and is recorded in a version matrix.

## 2. Naming map (SupportPilot → Servora)

| Thing | Value |
|---|---|
| Repo root / Compose project | `servora` |
| Java base package | `com.servora` (`com.servora.auth`, `com.servora.tenant`, …) |
| Maven artifact | `servora-core-api` |
| Python packages | `servora_ai` (AI service), `servora_worker` (document worker) |
| npm packages | `@servora/web`, `@servora/widget` |
| Database / roles | DB `servora`; roles `servora_owner` (migrations), `servora_app` (core API runtime), `servora_ai` (read-only retrieval), `servora_worker` (ingestion) |
| Env var prefix | `SERVORA_` |
| Widget loader | `servora-widget.js`, global `window.Servora` |
| RLS setting | `app.tenant_id` (unchanged from handbook) |

## 3. Architecture in brief

- **Core API** — Spring Boot modular monolith, package-by-feature. System of record for identity, tenancy, authorization, conversations, tickets, usage, audit. Orchestrates AI calls and validates their results.
- **AI service** — Python + FastAPI. Retrieval, RAG, provider adapter, structured output, evaluation. Never an authority for permissions or ticket state.
- **Document worker** — Python + Celery on Redis. Extraction, chunking, embedding, indexing; idempotent and retry-safe.
- **PostgreSQL + pgvector** — one shared schema, `tenant_id UUID NOT NULL` on every tenant-owned table, composite `(tenant_id, id)` foreign keys, RLS with `FORCE ROW LEVEL SECURITY`.
- **Redis** — Celery broker, rate-limit counters, cache. Never a source of truth.
- **Object storage** — private S3-compatible; MinIO locally so the code path matches deployment.
- **Frontends** — React + TypeScript + Vite + Tailwind dashboard (`apps/web`), and a separate widget build (`apps/widget`) mounted in an iframe by a small loader.

### Repository layout

```text
servora/
  apps/
    web/                 # React dashboard
    widget/              # iframe customer widget + loader
  services/
    core-api/            # Spring Boot modular monolith
      src/main/java/com/servora/
        auth/ tenant/ workspace/ membership/
        assistant/ knowledge/ conversation/ ticket/
        analytics/ usage/ notification/ common/
      src/main/resources/db/migration/
    ai-service/          # FastAPI: api/ rag/ retrieval/ generation/ embeddings/ evaluation/ security/
  workers/
    document-worker/     # Celery ingestion jobs
  infrastructure/
    docker/ database/ monitoring/
  docs/
    architecture/ database/ api/ security/ ai/
  docker-compose.yml
  .env.example
  README.md
```

## 4. Decisions to record as ADRs in M0

ADR-001 to ADR-007 come straight from the handbook. ADR-008 to ADR-014 are gaps the handbook leaves open; the recommendation is mine and each is confirmed or changed in M0.

| ADR | Decision | Status |
|---|---|---|
| 001 | Modular monolith + separate Python AI service | Handbook |
| 002 | Shared schema with `tenant_id`, RLS and tenant-aware FKs | Handbook |
| 003 | PostgreSQL + pgvector as the single database | Handbook |
| 004 | Private object storage, backend-controlled access | Handbook |
| 005 | Iframe widget with loader script | Handbook |
| 006 | Async worker for ingestion | Handbook |
| 007 | RAG only, no autonomous consequential actions | Handbook |
| 008 | **Migration ownership:** Flyway in core API owns the entire schema, including `document_chunks`. Python services ship no migrations. | Recommended |
| 009 | **AI/worker data access:** direct PostgreSQL with restricted roles under RLS. `servora_ai` is read-only on active chunks; `servora_worker` may insert chunks and update document processing state. Both set `app.tenant_id` transaction-locally from the tenant in the signed internal token. | Recommended |
| 010 | **Job dispatch from Java to Celery:** core API writes an outbox row in the same transaction as the document record; a dispatcher calls an authenticated internal enqueue endpoint on the Python side. Avoids hand-writing the Celery wire format in Java. | Recommended |
| 011 | **Dashboard sessions:** short-lived access JWT held in memory; rotating refresh token in an HttpOnly, Secure, SameSite cookie, stored hashed, with reuse detection. Argon2id password hashing via Spring Security. | Recommended |
| 012 | **Embeddings:** local Sentence Transformers model (handbook ref R9), with model id, revision and dimension stored per chunk. No API cost, deterministic tests. | Recommended |
| 013 | **LLM provider:** one real provider behind an adapter plus a fake provider for tests. Which provider is an open decision due before M3. | Open |
| 014 | **Customer streaming:** Server-Sent Events from core API to the widget; core API proxies the AI stream so the final state re-check stays on the Java side. | Recommended |

## 5. Milestones

Each milestone is an acceptance gate, not a calendar promise. Durations are my indicative estimates for one developer; the handbook notes a six-week total is possible only with disciplined scope. Work does not cross a milestone boundary without an explicit instruction.

| # | Milestone | Exit deliverable | Indicative effort |
|---|---|---|---|
| M0 | Repository and architecture baseline | Docs, version matrix, repo skeleton, CI stub | 2–3 days |
| M1 | Identity and tenant foundation | Two businesses share the app without seeing each other's data | 1.5–2 weeks |
| M2 | Assistant, widget config, knowledge ingestion | Each business has an independently indexed knowledge base | 1.5 weeks |
| M3 | RAG AI service | Assistant answers tenant-specific questions with verified citations | 1.5 weeks |
| M4 | Public widget and conversations | A business embeds a working assistant on a demo site | 1–1.5 weeks |
| M5 | Tickets and human agent workspace | An agent takes over and resolves an AI conversation safely | 1.5 weeks |
| M6 | Analytics, metering, platform admin | Business and platform operational visibility | 1 week |
| M7 | Hardening and release | Reproducible public demo with documented limitations | 1.5 weeks |

---

### M0 — Repository and architecture baseline

**Goal:** an agreed, documented foundation. No product features.

**Tasks**

1. Clone the empty Servora repo, set `main` as default, add branch protection and a PR template.
2. Verify and pin versions from official docs: Spring Boot release supporting Java 25, Flyway, pgvector Postgres image, Python base image, FastAPI, Pydantic, Celery, Redis, Node, React, Vite, Tailwind, Playwright. Record them in `docs/architecture/version-matrix.md`.
3. Write ADR-001 to ADR-014 under `docs/architecture/adr/`.
4. Create the directory skeleton in section 3 with placeholder READMEs.
5. Add `README.md`, `.gitignore`, `.editorconfig`, `.env.example` (placeholders only), coding conventions, and commit/branch conventions.
6. Add `docs/architecture/` system context and component diagrams, `docs/security/threat-model.md` (first draft from handbook section 13), `docs/database/` ERD draft.
7. Add a minimal `docker-compose.yml` with only `postgres` (pgvector) and `redis`, both with health checks and pinned tags.
8. Add a GitHub Actions workflow stub that lints Markdown and validates Compose config; later milestones extend it.
9. Place the renamed master prompt in the repo as the project-level agent instruction.
10. Write the milestone checklist (`docs/milestones.md`) with FR IDs per milestone.

**Exit gate:** `docker compose up` brings Postgres and Redis to healthy; `CREATE EXTENSION vector` succeeds; version matrix and ADRs are reviewed; nothing secret is committed.

---

### M1 — Identity and tenant foundation

**Requirements:** FR-001 to FR-007; first audit events (FR-041); health endpoints (FR-044).

**Backend**

1. Spring Boot project with Maven Wrapper, package-by-feature modules, global exception handler, error envelope `{error:{code,message,trace_id}}`, correlation-ID filter, OpenAPI.
2. Flyway migrations: `users`, `tenants`, `memberships`, `invitations`, `refresh_tokens`, `audit_events`, `platform_admins` (separate from workspace roles).
3. Database roles: `servora_owner` runs migrations and owns tables; `servora_app` is the runtime role with no ownership and no `BYPASSRLS`.
4. RLS enabled and forced on every tenant-owned table using the handbook policy pattern on `app.tenant_id`.
5. Auth: register, email verification (mail stubbed to a local catcher/log), login, refresh with rotation and reuse detection, logout/revoke. Login rate limiting.
6. Tenant context: resolve `workspaceId` from the route, verify active membership and role, then `set_config('app.tenant_id', …, true)` inside the transaction. One component does this; no service sets it ad hoc.
7. Workspace create/list/read/update; membership list, role/status change; invitations with hashed tokens and expiry; role matrix (Owner / Admin / Supervisor / Agent) enforced server-side.
8. Workspace creation seeds a default assistant and widget row placeholder only if their tables exist; otherwise deferred to M2.

**Frontend**

9. Vite + React + TypeScript + Tailwind app; typed API client; register, verify, login, workspace create, workspace switcher, team and invitations pages, workspace settings. Loading, empty, error and permission-denied states.

**Tests**

- Unit: role-permission matrix, membership rules, token rotation.
- Integration against real Postgres (Testcontainers) with RLS on: two tenants, multiple users.
- Cross-tenant ID-substitution tests for workspaces, members, invitations, audit events.
- Pooled-connection test: parallel requests for different tenants over a small pool prove context never leaks.
- RLS sanity: a query with no tenant context returns zero rows; the runtime role cannot disable RLS.

**Exit gate:** owner and invited agent can enter their workspace; neither can read or modify another tenant by changing IDs; all the above tests pass in CI.

---

### M2 — Assistant, widget configuration, and knowledge ingestion

**Requirements:** FR-008, FR-009, FR-011 to FR-019.

**Backend**

1. Migrations: `ai_assistants`, `assistant_versions` (immutable snapshots), `widgets`, `widget_allowed_origins`, `knowledge_sources`, `documents`, `document_chunks` (pgvector column, model id/revision/dimension, active flag), `outbox_events`. Composite tenant FKs and RLS on all.
2. Assistant config read/update; every update writes a new immutable version.
3. Widget public key generation/rotation, theme and position settings, allowed origins stored normalised with exact matching.
4. Storage abstraction with an S3 implementation (MinIO locally); generated object keys, never raw filenames.
5. Upload endpoint: extension allowlist (PDF, TXT, MD), content-sniffed MIME, byte limit, content hash, upload rate limit.
6. Document state machine `UPLOADED → QUEUED → PROCESSING → READY | FAILED`, plus `ARCHIVED`; list, archive, delete, reprocess endpoints.
7. Outbox dispatcher to the Python enqueue endpoint (ADR-010), authenticated with a short-lived signed internal token.

**Worker**

8. Celery worker with `servora_worker` DB role; task time, memory and page-count limits; no following of URLs embedded in documents.
9. Pipeline: fetch → extract → normalise → structural chunking with bounded size and small overlap (values in config and documented) → embed → write chunks and mark `READY` in one transaction.
10. Idempotency keyed on document id + version + content hash; bounded retries; safe error codes with no stack traces or extracted content.
11. Replace/delete deactivates old chunks in the same transaction that activates new ones.

**Frontend**

12. Assistant settings page, widget settings page (key, origins, theme, embed snippet preview), knowledge base page with upload, status polling, failure reason, reprocess and archive.

**Tests**

- Worker unit tests: chunking determinism, extraction of each format, malformed and oversized files.
- Integration: upload to `READY` end-to-end in Compose; retry after forced failure produces no duplicate chunks.
- Cross-tenant tests for assistants, widgets, origins, documents and chunks, including the worker role under RLS.
- Archived/replaced documents leave zero active chunks.

**Exit gate:** two tenants upload different documents, both reach `READY`, and each tenant's chunks are invisible to the other at the API and at the database-role level.

---

### M3 — RAG AI service

**Requirements:** FR-010, FR-025 to FR-029, FR-031 (FR-030 sentiment is post-MVP).

**Tasks**

1. FastAPI service with Pydantic request/response models, health/readiness, and internal auth: verifies a short-lived signed token from core API carrying tenant and conversation context.
2. Query embedding with the same model profile as indexed chunks; refuse to search across mismatched profiles.
3. Retrieval: exact similarity search over active chunks, tenant-filtered and under RLS via `servora_ai`. No HNSW yet.
4. Evidence gate: similarity threshold and minimum-evidence rules; nearest neighbour alone is not sufficient.
5. Prompt assembly: versioned prompt, bounded context, retrieved text labelled as untrusted data.
6. Provider adapter interface, one real provider (ADR-013), and a deterministic fake provider. Timeouts, bounded retry on transient errors, token caps, circuit-breaker fallback to "offer human support".
7. Structured output schema: `answer`, `intent`, `needs_escalation`, `escalation_reason`, `citations[]`. Malformed JSON handled as a failure path, not passed through.
8. Citation verification in the AI service and again in core API: every cited chunk id must be in the retrieved set for that tenant.
9. Core API orchestration client and the private playground endpoint `POST /workspaces/{id}/assistant/test`; playground UI showing answer, citations, and retrieval scores.
10. Persist model, prompt version, latency, token usage, retrieval ids/scores and outcome; no full prompts or documents in logs.
11. Evaluation harness and a labelled dataset for the TechStore and HotelPro fixtures with expected source chunks; baseline report in `docs/ai/`.

**Tests:** the ten required AI cases from handbook section 14.4 that apply before conversations exist — strong match, paraphrase, no evidence, conflicting documents, injection in the user message, injection inside a PDF, provider timeout/rate limit, malformed JSON, invalid citation ids — plus tenant-corpus correctness. All run on the fake provider with no paid calls.

**Exit gate:** playground answers each tenant's questions with verified citations, abstains on unsupported questions, and a recorded baseline of Precision@K / Recall@K exists with dataset, sample size, model version, thresholds and date.

---

### M4 — Public chat widget and conversations

**Requirements:** FR-020 to FR-024.

**Tasks**

1. Migrations: `customers`, `conversations` (status, `control_mode`, `version` for optimistic locking), `messages` (sender type, visibility, delivery status, model metadata), `message_citations`.
2. Public endpoints: widget config, session creation, start conversation, post message, history. Widget key resolved server-side; active state and exact origin checked.
3. Widget session tokens: short-lived, signed, bound to tenant, session, conversation, expiry and allowed actions. Cannot call any `/workspaces/**` endpoint or enumerate conversations.
4. Rate limits per widget, IP, session and tenant budget, backed by Redis. Idempotency key on message submission.
5. Message flow: store customer message → request AI answer when `AI_ACTIVE` → verify citations → re-read conversation in a transaction and check mode and version → persist and deliver, or suppress and record an event.
6. SSE streaming to the widget (ADR-014).
7. Widget app: loader script that mounts an iframe, chat UI, source references, request-a-human button, keyboard access, visible focus, responsive layout, offline and error states. Model and customer text rendered as text, never raw HTML.
8. Two static demo sites (TechStore, HotelPro) embedding their widgets.
9. Dashboard conversations list and read-only thread view with pagination.

**Tests**

- Session token scope: expired, tampered, wrong-conversation and admin-endpoint attempts all rejected.
- Origin allowlist: exact match passes; substring and look-alike origins fail.
- Rate-limit behaviour; duplicate message submission is deduplicated.
- Handbook acceptance test 4.4: asking TechStore's widget about hotel check-in returns no HotelPro chunks, the answer abstains, and no HotelPro identifiers appear in logs, response or citations.
- Playwright: embed, chat, citations displayed.

**Exit gate:** both demo sites chat against their own knowledge with citations, and public tokens are provably confined to their own conversation.

---

### M5 — Tickets and human agent workspace

**Requirements:** FR-032 to FR-037, FR-040.

**Tasks**

1. Migrations: `tickets` (public ticket number, one ticket per escalated conversation for MVP), `ticket_events`, `internal_notes`, `conversation_feedback`, `notification_events`; unique idempotency key on escalation.
2. Escalation policy: customer request, insufficient evidence, sensitive or out-of-scope request, repeated misunderstanding, service error, configured risk rules. Sentiment may adjust priority but never triggers alone.
3. Escalation transitions the conversation to `ESCALATION_PENDING` and creates the ticket idempotently in one transaction.
4. Assignment by supervisors; ticket status machine `OPEN → IN_PROGRESS → WAITING_FOR_CUSTOMER → RESOLVED → CLOSED`, kept separate from conversation control mode.
5. Atomic takeover endpoint: version-checked transition to `HUMAN_ACTIVE`. The M4 pre-delivery check then suppresses any in-flight AI answer and records the suppression.
6. Agent replies (`PUBLIC`), internal notes (`INTERNAL`), AI suggestions (`AI_SUGGESTION`, not sent until approved), system events. Public endpoints filter by visibility at the query, not in the serializer.
7. Resolve/close with outcome; customer rating endpoint and widget UI.
8. In-app notifications for assignment and new customer messages.
9. Agent workspace UI: three panes (queue, thread, ticket/customer context), control-mode indicator, Take Over action with conflicting controls disabled during transitions, separate composers for reply, note and suggestion.

**Tests**

- Unit: ticket transitions, escalation policy.
- Concurrency: takeover racing an AI answer, repeated many times; the AI message is never delivered after takeover.
- Idempotency: repeated and parallel escalation requests yield exactly one ticket.
- Internal notes never appear in any public endpoint response.
- Cross-tenant tests for tickets, events, notes and feedback.
- Playwright: full AI-to-human handoff through resolution and rating.

**Exit gate:** handbook journey 4.3 runs end to end, and the race test passes reliably.

---

### M6 — Analytics, metering, platform admin

**Requirements:** FR-038, FR-039, FR-041, FR-042; FR-043 (post-MVP, done last and only if the gate above it is met).

**Tasks**

1. Migrations: `plans`, `subscriptions`, `usage_events` (append-only). No payment provider.
2. Usage metering for AI requests, tokens, estimated cost, documents and conversations; plan-limit enforcement with clear error codes and a graceful widget message when a tenant is over budget.
3. Analytics endpoints and Overview/Analytics pages: conversation counts, AI-resolved versus escalated, ticket queues, response times, intent topics.
4. Usage & Plan page.
5. Audit event coverage review across membership, configuration, knowledge and ticket actions; audit log view for owners/admins.
6. Tenant data export and deletion at a scoped, documented level; retention settings.
7. Platform admin: list tenants, suspend/reactivate. Access is explicit, audited, and does not expose conversation content.

**Tests:** usage-limit unit and integration tests; cross-tenant tests for analytics, usage and audit; suspended tenants are rejected at dashboard and widget; platform-admin actions produce audit events.

**Exit gate:** dashboards show correct per-tenant numbers on the two-tenant fixture, and limits are enforced.

---

### M7 — Hardening and release

**Requirements:** FR-044, FR-045, and the non-functional targets in handbook section 5.

**Tasks**

1. Security review against OWASP API Security Top 10 and OWASP Top 10 for LLM Applications, testing the real attack paths; update the threat model with evidence.
2. Complete the cross-tenant negative suite for every resource family and publish results in `docs/security/`.
3. Full AI evaluation report: retrieval metrics, citation correctness, human-reviewed groundedness, escalation precision/recall, latency, cost per conversation.
4. Playwright suite covering the 11-step MVP demonstration script (handbook section 18).
5. Load test for non-AI endpoints against the P95 < 500 ms target, recording hardware, dataset and concurrency.
6. Compose hardening: non-root containers, pinned tags, internal networks, no public Postgres/Redis ports in the production profile, separate dev and prod configuration, resource limits.
7. CI: build, lint, unit and integration tests for all three stacks, dependency and image scanning.
8. Observability: structured logs, metrics, OpenTelemetry traces from browser through core API and AI service to the provider and database.
9. Backup and restore scripts for PostgreSQL and object storage, with a documented restore drill that has actually been run.
10. Accessibility checks for widget and dashboard.
11. Final README, API docs, developer and user guides, seed script, demo setup, known-limitations list. First deployment.

**Exit gate:** a fresh clone reaches a working two-tenant demo using only the documented commands, and every item in the definition of done holds.

---

## 6. Requirement coverage

| Milestone | Functional requirements |
|---|---|
| M1 | FR-001, 002, 003, 004, 005, 006, 007 |
| M2 | FR-008, 009, 011, 012, 013, 014, 015, 016, 017, 018, 019 |
| M3 | FR-010, 025, 026, 027, 028, 029, 031 |
| M4 | FR-020, 021, 022, 023, 024 |
| M5 | FR-032, 033, 034, 035, 036, 037, 040 |
| M6 | FR-038, 039, 041, 042, 043 (post-MVP) |
| M7 | FR-044, 045 |
| Post-MVP | FR-030 (sentiment classification) |

FR-041 (audit) and FR-044 (health/metrics) start in M1 and are completed in the milestone listed.

## 7. Rules that apply to every milestone

**Workflow** (master prompt section 15): inspect the repo and branch → read existing docs, migrations and code → summarise state and conflicts → propose a small task plan with files to change → implement only that milestone → add tests alongside → run formatters, linters, tests and builds → review the diff for secrets, unrelated edits and unscoped queries → update docs and `.env.example` → report work, decisions, commands, real test results and limitations → stop.

**Git:** one branch per milestone (`milestone/m1-identity-tenancy`), small commits, a PR into `main`, and a tag at each gate (`v0.1-m1` … `v1.0-m7`). Handbook FR IDs go in issues, PR titles and test names.

**Definition of done:** acceptance criteria met; code formatted and builds; relevant tests pass and are reported accurately; tenant id derived and validated server-side; every object access scoped and authorised; migrations work from a clean database; error and loading states handled; logs free of secrets and private content; docs and config examples updated; known limitations stated.

**Release blockers at any milestone:** a tenant-owned table without RLS; a repository method that fetches by id alone; a runtime DB role that owns tables or has `BYPASSRLS`; a public token that reaches a management endpoint; a secret in the repo; a test result claimed without being run.

## 8. Out of scope until the MVP is stable

Live billing, WhatsApp/social/email channels, voice, CRM or order-system integrations, autonomous refunds or account changes, multi-provider LLM routing and agent frameworks, fine-tuning, Kubernetes, HNSW or hybrid retrieval before a benchmark justifies them.

## 9. Main risks

| Risk | Mitigation | Where it is proven |
|---|---|---|
| Cross-tenant leakage | RLS, scoped repositories, composite FKs, restricted roles | M1 tests, extended every milestone |
| Hallucinated policy | Evidence gate, verified citations, abstention | M3 evaluation |
| Prompt injection | Untrusted-context labelling, no privileged tools, adversarial tests | M3, M7 |
| AI/human reply race | Atomic takeover, optimistic locking, pre-delivery re-check | M4 mechanism, M5 race test |
| Unsafe document parsing | Allowlist, limits, constrained worker, private storage | M2 |
| LLM cost or widget abuse | Rate limits, token caps, quotas | M4, M6 |
| Provider outage | Timeouts, bounded retry, human-support fallback | M3 |
| Scope creep | Milestone gates, section 8 deferred list | Every gate |

## 10. MVP demonstration (final acceptance)

1. Register TechStore and HotelPro as separate tenants.
2. Invite one agent to each workspace.
3. Upload different policy documents to each.
4. Wait until both indexes are ready.
5. Embed each widget on its own demo page.
6. Ask each widget questions only its own documents answer.
7. Ask an unsupported question; verify abstention or escalation.
8. Escalate a conversation, assign an agent, take over.
9. Generate an AI response in parallel with takeover; verify no late AI reply is sent.
10. Add an internal note; verify the customer cannot retrieve it.
11. Run the cross-tenant tests and show the output.

## 11. Next step

Run **M0 only**: clone the empty Servora repository, verify and pin versions from official documentation, write the ADRs and version matrix, create the skeleton, and stop for review. The open decision to settle before M3 is the LLM provider (ADR-013).
