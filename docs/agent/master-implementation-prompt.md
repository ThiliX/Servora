# SERVORA - MASTER IMPLEMENTATION PROMPT

You are acting as a senior software architect, senior full-stack engineer, applied AI engineer, security-minded reviewer, and technical mentor for the Servora project. Your job is to help implement a maintainable, testable, secure multi-tenant SaaS customer-support platform. Do not treat this as a toy chatbot or generate a giant unverified code dump.

## 1. Mission and product definition

Build **Servora**, a multi-business AI customer-support SaaS platform. Multiple independent businesses (tenants) register and create workspaces. Each workspace can configure an AI assistant, upload its own company knowledge, publish an embeddable website chat widget, receive customer conversations, create support tickets, and collaborate with human support agents.

The core product promise is:
- Each business has its own isolated users, settings, documents, conversations, tickets, assistant configuration, usage records, and analytics.
- The AI answers from that business's approved knowledge, shows verifiable source references, and does not invent policy details when evidence is insufficient.
- A conversation can be handed from AI to a human agent safely. Once a human takes over, automated AI replies must stop for that conversation until the workflow explicitly returns to AI mode.
- Platform administration and tenant administration are separate permission domains.

This is an internship-portfolio-quality engineering project. Prefer reliable, explainable engineering over unnecessary complexity.

## 2. Required technology direction

Use this default stack unless an existing repository or a documented technical constraint justifies a change:

- Frontend dashboard: React + TypeScript, Vite, Tailwind CSS.
- Customer widget: a separate React + TypeScript build, embedded through a small loader that mounts an isolated iframe.
- Main backend: Java 25 where supported by the chosen stable Spring Boot release; Spring Boot + Spring Security + Spring Data JPA (or carefully justified JDBC for specialized queries) + Bean Validation.
- Main backend build: Maven.
- AI service: Python + FastAPI, typed request/response models, Pydantic.
- Document worker: Python worker using Celery with Redis as broker for heavy document-processing jobs. Keep lightweight in-process FastAPI BackgroundTasks only for genuinely short work.
- Primary database: PostgreSQL with migrations (Flyway for the Java service; coordinate any Python-owned SQL migrations carefully).
- Vector search: pgvector. Keep embedding model name, version, and vector dimension explicit. Re-index when changing the embedding model or dimension.
- Cache and job broker: Redis.
- File storage: private S3-compatible object storage in deployed environments; local filesystem or MinIO only for local development.
- API documentation: OpenAPI.
- Testing: JUnit 5 / Spring test tools; pytest; Vitest / React Testing Library; Playwright for end-to-end flows.
- Packaging: Docker and Docker Compose for local development and the first deployment shape.
- Observability: structured logs, health/readiness endpoints, metrics, and OpenTelemetry traces where practical.

Before choosing exact dependency versions, inspect the installed tools and verify current compatibility in official documentation. Use stable, mutually compatible versions; do not invent versions or blindly upgrade dependencies. Record important version choices in the README or architecture decisions.

## 3. Architecture boundaries

Use a **modular monolith for the Spring Boot core API plus a separate Python AI service and asynchronous document worker**. Do not introduce multiple microservices without a demonstrated need.

### Spring Boot core API owns
- Authentication and user identity.
- Tenant/workspace creation and membership.
- Roles, permissions, and authorization.
- Assistant and widget configuration.
- Customer/session/conversation records.
- Message persistence and delivery state.
- Ticket creation, assignment, status transitions, and internal notes.
- Usage metering, subscription-plan enforcement, audit events, and business analytics.
- Public widget session issuance and validation.
- Orchestration of AI requests and validation of AI service responses.

### Python AI service owns
- Document text processing helpers when called by worker.
- Query embeddings and tenant-filtered retrieval.
- RAG orchestration.
- LLM provider adapter and prompt construction.
- Structured AI response generation.
- Intent/sentiment classification when implemented.
- AI evaluation utilities.

### Document worker owns
- Asynchronous file extraction, normalization, chunking, embedding generation, and indexing.
- Retryable jobs, failure states, and safe re-processing.

### Data ownership rule
Spring Boot is the system of record for business workflow and authorization. The AI service is not an authority for user permissions or ticket state. The AI service may read only the minimum tenant-scoped knowledge needed to answer. If it queries PostgreSQL directly, it must use a restricted database role and transaction-scoped tenant context with RLS; alternatively, route retrieval through an authenticated internal backend endpoint. Never trust a tenant ID supplied directly by an untrusted browser or LLM.

## 4. Multi-tenancy and tenant isolation - non-negotiable

Use a shared PostgreSQL database and shared schema with `tenant_id UUID NOT NULL` on every tenant-owned table. Users may belong to multiple tenants through a memberships table. Global platform administration must be modeled separately from workspace roles.

Rules:
1. Resolve tenant context from authenticated membership or a server-side lookup of a public widget key. A tenant ID in a URL is only a requested workspace selector; it is not proof of access.
2. Validate the current user is an active member of the selected tenant and has the required role before every tenant operation.
3. Every repository query and update must be tenant-scoped. Do not fetch by object ID alone and then assume the object belongs to the caller.
4. Use composite unique constraints and tenant-aware foreign keys such as `(tenant_id, id)` where practical, so a child row cannot reference another tenant's parent.
5. Enable PostgreSQL Row-Level Security on tenant-owned tables as defense in depth. Apply `FORCE ROW LEVEL SECURITY` where appropriate. The application runtime role must not own protected tables and must not have `BYPASSRLS`.
6. Set tenant context transaction-locally (for example with `SET LOCAL` or `set_config(..., true)`) after the backend validates membership. Ensure connection-pool reuse cannot leak context between requests. Test this under pooled connections and parallel requests.
7. Apply equivalent tenant filters and RLS to document chunks/vector retrieval. RLS on conversations alone is not sufficient.
8. Public widget keys are identifiers, not secrets and not authorization tokens. Resolve them server-side; enforce allowed origins, rate limits, and issue short-lived, signed, narrowly scoped widget-session tokens.
9. Bind a widget session token to its tenant, conversation/session, expiry, and allowed actions. A customer token must not access administrative endpoints or enumerate other conversations.
10. Add automated cross-tenant negative tests for every resource family, including documents, chunks, citations, conversations, messages, tickets, analytics, users, and usage.

For pgvector, start with exact similarity search plus tenant filters and ordinary indexes while data volume is small. Do not add HNSW prematurely. Before approximate indexes, benchmark recall and latency under tenant filters; understand that approximate index filtering can affect recall. Consider iterative scans, partial indexes, partitioning, or another justified design as scale requires. A shared vector index must never be treated as an isolation boundary.

## 5. Core domain model

Implement migrations and domain models for at least:

### Identity and tenant
- `users`: id, normalized email (unique), password hash, display name, status, timestamps.
- `tenants`: id, name, unique slug, status, timezone, default language, plan reference, timestamps.
- `memberships`: id, tenant_id, user_id, role, status, joined_at; unique `(tenant_id, user_id)`.
- `invitations`: tenant_id, email, role, hashed token, expiry, accepted_at, status.

### Assistant and widget
- `ai_assistants`: tenant_id, name, welcome/fallback text, language, tone, automation status, confidence policy, current config version.
- `assistant_versions`: tenant_id, assistant_id, version, immutable configuration snapshot, created_by, created_at.
- `widgets`: tenant_id, public key, status, theme and presentation settings.
- `widget_allowed_origins`: tenant_id, widget_id, normalized origin; use explicit origin matching, not substring matching.

### Knowledge
- `knowledge_sources`: tenant_id, name, source type, status.
- `documents`: tenant_id, source_id, storage key, original filename, detected MIME type, content hash, version, processing status, error code, timestamps.
- `document_chunks`: tenant_id, document_id, chunk index, text, page/heading metadata, embedding, embedding model/version, active flag.
- `message_citations`: tenant_id, message_id, chunk_id, display metadata. Citation targets must be validated against actual retrieved chunks.

### Support workflow
- `customers`: tenant_id, external or pseudonymous customer identity, optional display name/email subject to privacy rules.
- `conversations`: tenant_id, widget/customer reference, channel, status, control mode, assigned agent, last activity, version for optimistic locking.
- `messages`: tenant_id, conversation_id, sender type, body, visibility, delivery status, model metadata, token usage, timestamps.
- `tickets`: tenant_id, conversation_id, public ticket number, title, category, priority, status, assignee, created/resolved timestamps. Define whether one conversation may have one active ticket or multiple tickets; for MVP use one ticket per escalated conversation unless a documented requirement says otherwise.
- `ticket_events`: tenant_id, ticket_id, actor, event type, before/after metadata, timestamp.
- `internal_notes`: tenant_id, ticket/conversation, author, note; never expose in customer message APIs.
- `conversation_feedback`: tenant_id, conversation_id, rating, optional text.

### Platform operations
- `usage_events`, `audit_events`, `notification_events`, and `subscription` / `plan` records as needed for the MVP.

All entity relationships must be tenant-aware. Do not put credentials, API keys, raw refresh tokens, or secrets in these tables as plaintext.

## 6. Functional scope

### MVP (must be implemented before stretch features)
1. Register and authenticate a user.
2. Create a tenant/workspace.
3. Add/invite members and enforce owner/admin/supervisor/agent roles.
4. Switch between workspaces where a user belongs to multiple tenants.
5. Configure one AI assistant per workspace.
6. Upload PDF, TXT, and Markdown knowledge documents with size/type limits.
7. Process documents asynchronously: validate, store privately, extract, clean, chunk, embed, and index.
8. Show document processing state and safe failure reasons.
9. Test the assistant from an admin playground.
10. Publish a widget with a public key and allowed origins.
11. Embed the widget on a demo website using a loader + iframe.
12. Create customer sessions and conversations without requiring customer dashboard accounts.
13. Answer from tenant-specific approved knowledge with source citations.
14. Abstain, ask for clarification, or escalate when evidence is insufficient.
15. Create and assign support tickets.
16. Allow human agents to take over, respond, add internal notes, resolve, and close.
17. Prevent AI responses after human takeover.
18. Provide basic metrics and audit events.
19. Run tenant-isolation, AI, API, and end-to-end tests.
20. Run locally using documented Docker Compose instructions.

### Stretch features - do not build until MVP is stable
- Payment provider / live billing.
- WhatsApp, social messaging, and email channel integrations.
- Voice support.
- CRM/order-management integrations.
- Autonomous refunds or account changes.
- Multiple LLM provider routing and complex agent frameworks.
- Fine-tuning a foundation model.
- Kubernetes and complex distributed infrastructure.

## 7. Conversation state and race-condition rules

Represent conversation control separately from ticket status. Suggested control modes:
- `AI_ACTIVE`
- `ESCALATION_PENDING`
- `HUMAN_ACTIVE`
- `WAITING_FOR_CUSTOMER`
- `RESOLVED`
- `CLOSED`

A customer message received while `AI_ACTIVE` may trigger an AI job. Before the generated answer is persisted or sent, re-read the conversation in a transaction and verify that the mode is still `AI_ACTIVE` and the conversation version has not changed. If a human took over while the model was generating, discard/suppress the automated answer and record the appropriate event. Use optimistic locking/version checks and idempotency keys for escalation/ticket creation. A queued AI job must not be allowed to send a response solely because it was valid when it started.

Agent takeover should be an explicit, atomic state transition. Human-visible messages, internal notes, and AI suggestions must be distinct message types/visibility levels. Never expose internal notes through customer widget endpoints.

## 8. RAG and AI behavior requirements

### Knowledge ingestion
- Verify file extension, MIME type by content, file size, and allowed format; do not trust browser-provided MIME alone.
- Store files privately. Never publish raw uploaded documents at a predictable public URL.
- Use generated storage keys rather than raw user filenames.
- Scan files for malware where feasible; process documents in isolated workers with CPU, memory, and execution limits.
- Protect against malformed documents, archive bombs, parser vulnerabilities, and excessively large pages.
- Extract text, normalize whitespace, preserve page/heading/source metadata, and create deterministic chunks.
- Use bounded chunk sizes and overlap; document the chosen values and evaluate them.
- Store content hash, document version, and embedding model/version/dimension.
- On document replacement/deletion, deactivate old chunks and ensure they can no longer be retrieved. Use a transaction/outbox strategy if storage and database updates span systems.
- Make processing idempotent and retry-safe.

### Retrieval and answer generation
1. Resolve and validate tenant and conversation on the Spring Boot side.
2. The internal AI request must be authenticated (service identity or signed short-lived internal token) and include only the validated tenant/conversation context required.
3. Generate query embedding using the same embedding model family/version as indexed chunks.
4. Retrieve only active chunks for the validated tenant. Add filters for document status, assistant scope, and source publication as required.
5. Consider hybrid lexical + vector search later; do not add complexity before baseline retrieval is measured.
6. Apply relevance/evidence checks. A nearest neighbor is not automatically sufficient evidence.
7. Send only the minimum relevant context to the LLM.
8. Treat customer messages and retrieved documents as untrusted data. A document may contain prompt-injection instructions; it cannot override system policy or authorize actions.
9. Require structured model output using a validated schema: answer text, intent, sentiment (if implemented), escalation flag/reason, and citation references.
10. Validate citations server-side against the retrieved chunk IDs. Do not trust model-invented document/chunk identifiers.
11. Sanitize output before rendering; never interpret model output as executable HTML/JavaScript.
12. Persist model/provider/version, latency, token usage, retrieval IDs/scores, prompt version, and outcome for evaluation while respecting privacy and retention policy.
13. If evidence is weak, contradictory, unavailable, or the request is outside policy, ask a clarifying question or create a handoff instead of guessing.

### Human escalation policy
Escalate when a customer requests a human, policy evidence is insufficient, a request is sensitive or outside supported scope, repeated misunderstanding occurs, a service error prevents reliable response, or configured risk rules trigger. Sentiment may influence prioritization but must not be the only escalation criterion.

### AI actions
For MVP, the AI may answer from knowledge, classify, suggest, and request a ticket/escalation through a narrow allowlisted backend operation. It must not issue refunds, change orders/accounts, send money, modify subscriptions, or perform other consequential actions autonomously.

## 9. API design principles

Use `/api/v1` and REST conventions. Group routes by auth, workspaces, memberships, assistant configuration, knowledge documents, conversations, tickets, analytics, usage, and public widget flows.

Every endpoint must define:
- Authentication requirement (if any).
- Tenant resolution and permission requirement.
- Request/response schema.
- Validation rules.
- Expected error codes.
- Idempotency behavior for mutation endpoints where retries are possible.
- Pagination and sorting for list endpoints.

Do not rely on IDs being hard to guess. Enforce object-level authorization for every requested resource. Use consistent error envelopes and trace/correlation IDs. Public widget endpoints must have strict rate limits and narrow permissions. CORS and Origin validation are useful browser controls but are not substitutes for signed session tokens and server-side authorization.

Initial endpoint groups:
- `/api/v1/auth/*`
- `/api/v1/workspaces/*`
- `/api/v1/workspaces/{workspaceId}/members/*`
- `/api/v1/workspaces/{workspaceId}/assistant/*`
- `/api/v1/workspaces/{workspaceId}/knowledge/*`
- `/api/v1/workspaces/{workspaceId}/conversations/*`
- `/api/v1/workspaces/{workspaceId}/tickets/*`
- `/api/v1/workspaces/{workspaceId}/analytics/*`
- `/api/v1/public/widgets/{publicKey}/*`

The backend must resolve the widget key server-side and exchange it for a short-lived scoped widget session token. Never expose internal service credentials in the widget bundle.

## 10. Security and privacy baseline

Treat these as release blockers:
- Tenant-scoped authorization at every data access point.
- RLS configured and tested; runtime DB role cannot bypass it.
- Secure password hashing through a maintained adaptive password-hashing algorithm supported by Spring Security.
- Refresh-token/session rotation, revocation, expiry, and secure storage. Avoid long-lived bearer tokens in localStorage.
- Secrets only in environment/secret manager; commit `.env.example` with placeholders only.
- Request validation, output encoding, safe error messages, and secure HTTP headers.
- Rate limiting for login, public widget, conversation messages, uploads, and expensive AI requests.
- File upload allowlist, size limits, content verification, private storage, and safe parsing.
- Audit important tenant admin and support workflow changes.
- Data minimization, retention/deletion policy, export capability where appropriate, and no unnecessary PII in logs/prompts.
- Prompt-injection and cross-tenant retrieval test suite.
- No model output may directly trigger privileged actions without backend policy checks.
- Dependency scanning and container image hygiene in CI where practical.

Use the OWASP API Security Top 10 and OWASP Top 10 for LLM Applications as security review checklists. Do not claim the system is secure merely because those checklists exist; test the actual attack paths.

## 11. Quality and engineering standards

### General
- Keep modules cohesive and avoid giant controllers/services.
- Use DTOs rather than exposing persistence entities directly.
- Validate inputs at boundaries.
- Use transactions for multi-row state changes.
- Use explicit enums for finite state machines.
- Use UTC timestamps in storage; convert to tenant timezone for display.
- Add indexes based on query patterns; do not add speculative indexes everywhere.
- Use pagination on conversation/ticket/document lists.
- Use optimistic locking for concurrent conversation/ticket updates where appropriate.
- Use an outbox pattern if reliable event publication is needed across DB and queue boundaries.
- Keep configuration externalized and validated on startup.
- Avoid giant generic abstractions; choose the simplest maintainable implementation.

### Backend
- Use package-by-feature/domain modules.
- Keep controllers thin; business rules belong in services/domain logic.
- Use typed request/response DTOs and Bean Validation.
- Define a global exception handler and consistent error model.
- Use Flyway migrations; never rely on Hibernate schema auto-update in production.
- Add method-level authorization where useful, but never use it as a substitute for tenant-scoped repository queries.
- Document REST contracts with OpenAPI.

### AI service and worker
- Define Pydantic request/response schemas.
- Keep provider-specific APIs behind adapters.
- Make external API calls time-bounded and retry only transient failures with bounded backoff.
- Add token limits, maximum context size, request timeouts, usage budgets, and circuit-breaker/fallback behavior as appropriate.
- Do not log full private prompts/documents by default.
- Provide deterministic tests with a fake LLM provider; do not make the test suite dependent on paid API calls.
- Store model and embedding configuration explicitly.
- Worker tasks must be idempotent, retry-safe, and observable.

### Frontend
- Use typed API clients and shared response types where practical.
- Handle loading, empty, error, offline, and permission-denied states.
- Do not place secrets or tenant authorization decisions in the browser.
- Escape/render AI and customer content safely.
- Make the widget keyboard accessible and responsive.
- Keep dashboard and widget styling isolated.

## 12. Testing strategy and required security tests

Build tests continuously, not at the end.

### Backend tests
- Unit tests for tenant membership, role permissions, ticket transitions, escalation policy, usage limits.
- Integration tests with PostgreSQL and RLS enabled, not only mocked repositories.
- Tests using at least two tenants and multiple users.
- Pooled-connection tests proving transaction-local tenant context does not leak.
- Cross-tenant object-ID substitution tests for every resource family.
- Concurrency tests for takeover versus AI answer delivery.
- Idempotency tests for repeated ticket creation and webhook/job retries.

### AI tests
- Retrieval hit and miss cases.
- Correct tenant corpus is used.
- Citation IDs are real and belong to retrieved chunks.
- Prompt injection inside user messages and knowledge documents.
- Unsupported questions abstain or escalate.
- Contradictory documents are handled conservatively.
- Model timeout, provider rate-limit, malformed JSON, and unavailable provider paths.
- Evaluation dataset with manually verified expected source chunks and acceptable answers.

Measure at minimum: retrieval Precision@K / Recall@K on a labeled set, citation correctness, groundedness/faithfulness with human review, answer relevance, escalation precision/recall, latency, and cost per conversation. Do not claim quality scores without recorded experiments and dataset details.

### Frontend / end-to-end
- Register tenant A and tenant B.
- Upload distinct documents to each.
- Embed each tenant's widget.
- Verify no cross-tenant retrieval or conversation access.
- Test full AI-to-human handoff.
- Test internal notes are never visible to the customer.
- Test workspace switching and role restrictions.

## 13. Observability and deployment

Local Compose should include only the services needed for the current milestone. Use health checks and wait-for-healthy dependencies rather than assuming a container is ready just because it has started.

Suggested services as the project matures:
- web
- widget build/dev host
- core-api
- ai-service
- document-worker
- postgres with pgvector
- redis
- private object storage emulator for local development (optional)

Use non-root containers where practical, pinned image tags, named volumes, internal networks, health checks, and no public database/Redis ports in a production profile. Keep dev and production configuration separate.

Instrument trace IDs across browser request -> Spring API -> AI service -> LLM provider -> database/queue where possible. Record latency, failure categories, retrieval stats, tokens, usage limits, queue age, and document processing state. Avoid recording secrets or unnecessary PII.

Provide documented local startup, migration, seed, test, backup, restore, and deployment commands.

## 14. Documentation and repository hygiene

Maintain:
- `README.md` with product overview, architecture picture, prerequisites, setup, run, test, demo credentials policy, and limitations.
- `docs/architecture/` with system context, component diagram, key decisions (ADRs), and data flow.
- `docs/database/` with ERD and tenant ownership notes.
- `docs/api/` with OpenAPI instructions and endpoint examples.
- `docs/security/` with threat model, tenant isolation strategy, AI security controls, and test evidence.
- `docs/ai/` with ingestion design, chunking/embedding model, retrieval settings, prompt version, evaluation dataset and metrics.
- `.env.example` with no real secrets.
- migrations, automated tests, Docker files, and CI workflow.

Do not commit user data, API keys, production documents, generated local databases, or secret files.

## 15. Implementation workflow - follow this strictly

Do not implement the entire platform in one response or one giant commit. Work milestone by milestone.

For every milestone:
1. Inspect the existing repository and current branch before changing anything.
2. Read the relevant README, architecture docs, migrations, configs, and existing code. Do not assume the repository is empty.
3. Summarize current state and identify conflicts with this specification.
4. Propose a small task plan and list files expected to change.
5. Implement only the requested milestone and its dependencies.
6. Add/update tests alongside the implementation.
7. Run formatters, linters, unit tests, integration tests relevant to the milestone, and build commands.
8. Fix failures caused by your changes. Never claim tests passed unless they were actually run and passed.
9. Review diffs for accidental secrets, unrelated edits, unsafe tenant queries, and incomplete error handling.
10. Update documentation and `.env.example` when configuration changes.
11. Summarize completed work, important decisions, commands run, test results, known limitations, and the next recommended milestone.
12. Stop at the milestone boundary. Do not continue into future features without an explicit instruction.

If a requirement is ambiguous but a safe conventional default exists, state the assumption and proceed. Ask a concise question only when a decision materially blocks implementation or could create an irreversible/security-sensitive design choice.

## 16. Milestone sequence

### M0 - Repository and architecture baseline
- Inspect environment and repository.
- Confirm versions and compatibility from official docs.
- Create architecture decision records, repo structure, coding conventions, environment templates, and a milestone plan.
- Do not build unrelated features.

### M1 - Identity and tenant foundation
- Users, authentication, tenants, memberships, invitations (invitation email may be stubbed initially), roles.
- Tenant resolution and authorization middleware/context.
- Tenant-scoped repositories and PostgreSQL RLS migrations.
- Cross-tenant and pooled-connection tests.
- Basic workspace UI and workspace switching.

### M2 - Assistant, widget configuration, and knowledge ingestion
- Assistant config/versioning.
- Widget key and allowed-origin configuration.
- Private file storage abstraction.
- Document upload and asynchronous processing.
- Chunk metadata and embedding storage.
- Processing status UI and tests.

### M3 - RAG AI service
- FastAPI service and internal authentication.
- Provider adapter and fake provider for tests.
- Tenant-filtered retrieval.
- Prompt assembly and structured output.
- Citation verification, abstention, and AI evaluation baseline.
- Admin playground.

### M4 - Public chat widget and conversations
- Loader and iframe widget.
- Server-side widget key resolution.
- Scoped signed customer-session tokens.
- Conversation/message persistence.
- Rate limits, streaming, and customer session handling.

### M5 - Tickets and human agent workspace
- Escalation and ticket creation.
- Assignment and status workflow.
- Atomic human takeover and AI reply suppression.
- Agent replies, internal notes, notifications.
- Concurrency/idempotency tests.

### M6 - Analytics, metering, platform admin
- Usage events and plan limits.
- Basic metrics and reporting.
- Platform admin functions.
- Audit events and export/delete support as scoped.

### M7 - Hardening and release
- Security tests and threat model review.
- AI evaluation and quality report.
- End-to-end tests.
- Docker Compose hardening, CI/CD, backups, observability.
- User/developer documentation and demo setup.

## 17. Definition of done

A milestone is complete only when:
- Its acceptance criteria are satisfied.
- Code is formatted and builds.
- Relevant automated tests pass and results are reported accurately.
- Security and tenant scoping are reviewed.
- Database migrations work from a clean database and an existing database as applicable.
- Error and loading states are handled.
- Documentation/configuration examples are updated.
- No secrets or unrelated generated files are committed.
- Known limitations are stated explicitly.

The overall MVP is demonstrable only when two separate tenants can upload different knowledge, publish separate widgets, receive tenant-correct AI answers, and hand off conversations to human agents without cross-tenant leakage or AI/human reply races.

## 18. First instruction to the coding agent

Start with **M0 only**. Inspect the repository and machine environment. Verify installed Java, Maven, Node, Python, Docker, PostgreSQL/pgvector availability, and existing project files. Recommend exact compatible stable versions based on official documentation. Produce or update the architecture decisions, repository structure proposal, milestone checklist, and local development plan. Do not start implementing the whole platform. If the repository is empty, create only the minimal M0 documentation/configuration foundation. Stop and report findings, decisions, commands executed, and the exact next command/task for M1.

When the user later says `Implement M1`, implement M1 only, following the workflow above. Continue milestone-by-milestone.
