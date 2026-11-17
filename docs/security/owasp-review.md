# OWASP review

Review of the MVP against the [OWASP API Security Top 10 (2023)](https://owasp.org/API-Security/editions/2023/en/0x11-t10/) and the [OWASP Top 10 for LLM Applications (2025)](https://genai.owasp.org/llm-top-10/), done in M7 on 2026-11-17. Each entry names the attack path that was tested and where the test lives. A checklist is a prompt for tests, not proof of security. Where something is not tested or not built, this document says so.

Test names refer to the core API (`services/core-api/src/test`), the AI service (`services/ai-service/tests`), the widget (`apps/widget/src/widget.test.ts`) and the dashboard (`apps/web/src`).

## OWASP API Security Top 10 (2023)

### API1: Broken object-level authorization

- **Attack tested:** tenant B's owner calls every tenant-scoped endpoint with tenant A's workspace ID, and again with tenant A's conversation, ticket, document, member and invitation IDs inside B's own workspace path. Every call returns `404`, and a fingerprint of A's data is unchanged afterwards. Lists in B's workspace never contain A's IDs. `CrossTenantMatrixIntegrationTest`.
- **Within a tenant:** an agent cannot open a ticket assigned to someone else. `TicketWorkflowIntegrationTest.rolesAndOwnershipAreEnforced`.
- **Defence in depth:** the same refusals hold at the database through Row-Level Security, for the runtime, worker and AI roles. `RowLevelSecurityIntegrationTest`, AI `test_the_ai_role_is_read_only_and_sees_nothing_without_a_tenant`.

### API2: Broken authentication

- **Tested:** password guessing is blocked per account and address; a forged `X-Forwarded-For` does not reset the count; refresh tokens rotate and a replayed token revokes the family; refresh and logout need a custom header; the refresh cookie is `HttpOnly`, `Secure`, `SameSite=Strict`. `AuthFlowIntegrationTest`, `LoginAttemptLimiterTest`.
- **Changed in M7:** the limiter moved from memory to Redis, so it holds across instances and restarts, and gained per-account and per-address limits against attacks spread over many addresses or many accounts. Keys are hashed.
- **Not built:** multi-factor authentication. An access token stays valid for up to 15 minutes after a user is disabled.

### API3: Broken object property level authorization

- **Controls:** responses are explicit records, never entities; updates name the fields they change. Members cannot change their own role; admins cannot change owners. `RoleEnforcementIntegrationTest`.
- **Tested exposure:** the workspace export contains no password or token hashes, and the platform workspace list contains no conversation content or email addresses. `UsageAnalyticsAndAdminIntegrationTest`.
- **Not done:** an automated review of every response shape against a list of allowed fields.

### API4: Unrestricted resource consumption

- **Limits, all tested:** widget sessions and messages per address and per conversation, history polling, uploads per workspace, playground questions per workspace, a daily AI budget per workspace, monthly plan limits on AI requests, tokens, conversations and documents, exports per hour, and registrations per address and mailbox. Page sizes are capped at 100; uploads at 10 MB; model output at a configured token limit; AI calls time out and a circuit breaker stops calls to a failing provider.
- **Behaviour when Redis is down:** limited operations are refused rather than left unlimited.
- **Load:** at 100 concurrent users (418 requests per second) the non-AI endpoints stayed at a P95 of 15 ms with no failures ([load test](../testing/load-test.md)). Saturation was not measured.

### API5: Broken function level authorization

- **Tested:** the role matrix is asserted independently of its implementation (`PermissionTest`) and enforced over HTTP for all four roles (`RoleEnforcementIntegrationTest`). Platform endpoints return `404` to workspace owners. Owner-only data controls refuse admins.

### API6: Unrestricted access to sensitive business flows

- **Flows considered:** sign-up, widget sessions, AI answers, exports. Each has a rate limit, and AI answers also count against the plan. Escalation is idempotent per conversation (`parallelEscalationsCreateExactlyOneTicket`).
- **Not built:** CAPTCHA or other bot detection on sign-up and the widget.

### API7: Server-side request forgery

- **Review:** the core API makes outbound calls only to configured internal URLs (AI service, document worker, object storage). User-supplied URLs (the workspace logo) are stored, never fetched. Document parsing does not follow links in documents. The AI service calls only the configured provider.

### API8: Security misconfiguration

- **Tested:** every API response carries `Content-Security-Policy: default-src 'none'`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer` and a restrictive `Permissions-Policy`, including errors and public widget responses (`SecurityHeadersIntegrationTest`). CORS allows only each widget's listed origins (`corsPreflightIsAnsweredOnlyForAllowedOrigins`). Errors return a fixed envelope without stack traces. Health details are never shown. Forwarded headers are not trusted.
- **Production profile (M7 phase 3a):** only the gateway and widget host publish ports; the data network has no internet access; containers run as non-root with read-only filesystems and no capabilities; the API holds no owner password and the API documentation is disabled. The CI job checks the published ports and the absence of the owner password on every pull request ([ADR-021](../architecture/adr/0021-production-deployment-profile.md)).

### API9: Improper inventory management

- **Controls:** one API version (`/api/v1`), an OpenAPI document generated from the code, the endpoint tables in `docs/api/README.md`, and the endpoint inventory in `CrossTenantMatrixIntegrationTest`, which a new endpoint must join.
- **Decided:** the OpenAPI document and Swagger UI are disabled in the production profile.

### API10: Unsafe consumption of APIs

- **Tested:** the core API does not trust the AI service. It checks that every cited chunk belongs to the tenant and was given to the model, and replaces anything else with the fallback message (`PlaygroundIntegrationTest`). The AI service validates provider output against a schema and maps malformed, truncated or refused output to the fallback (`test_bad_model_output_is_never_shown_to_the_customer`, provider tests).

## OWASP Top 10 for LLM Applications (2025)

### LLM01: Prompt injection

- **Tested:** instructions in a customer message stay inside their tag and cannot reach the system instructions; instructions inside a document are escaped and cannot forge a citation (`test_injection_in_the_customer_message_stays_inside_its_tag`, `test_injection_inside_a_document_is_escaped_and_cannot_forge_a_citation`). Whatever the model says, it cannot act: escalation is decided by backend policy and citations are checked by the core API.
- **Limitation:** tested with the fake model. A live model's resistance is part of the AI evaluation report (M7 phase 4).

### LLM02: Sensitive information disclosure

- **Tested:** each tenant is answered only from its own documents, through retrieval under Row-Level Security as a read-only role (`test_each_tenant_is_answered_only_from_its_own_corpus`, `aTechStoreQuestionCanNeverBeAnsweredFromHotelProKnowledge`). Internal notes and other conversations are never put in the prompt. Logs hold no questions, prompts or answers.

### LLM03: Supply chain

- **Controls:** pinned model and library versions; the embedding model is baked into the image and loaded offline; secret and dependency scanning in CI (below).

### LLM04: Data and model poisoning

- **Scope:** no training or fine-tuning. Only a workspace's owners and admins can add documents, which affect only that workspace's answers. Answers must cite the documents they rely on, so a poisoned document is visible in the citation.

### LLM05: Improper output handling

- **Tested:** the widget renders model output as text; a hostile `<img onerror>` and `<script>` are shown literally (`widget.test.ts`). The dashboard renders through React, which escapes text. Model output never reaches SQL, a shell or a URL.

### LLM06: Excessive agency

- **Control:** the model has no tools (ADR-007). It answers or reports that a person is needed; the backend policy decides (`TicketRulesTest`). AI suggestions for agents are drafts that a person must send.

### LLM07: System prompt leakage

- **Review:** the system prompt holds instructions only: no keys, tenant identifiers of other workspaces, or internal URLs. Leaking it reveals nothing that grants access.

### LLM08: Vector and embedding weaknesses

- **Tested:** chunks are tenant-owned under RLS, the AI role is read-only, retrieval filters to active chunks of ready documents with the same embedding profile (`test_only_active_chunks_of_ready_documents_with_the_same_profile_are_searched`).

### LLM09: Misinformation

- **Controls:** an evidence threshold below which the model is not asked; answers must cite; ungrounded and uncited answers are replaced by the fallback; conflicting sources escalate (`test_no_evidence_abstains_without_calling_the_model`, `test_conflicting_documents_reach_the_model_together_and_escalate`).
- **Limitation:** answer quality is not yet measured (M7 phase 4).

### LLM10: Unbounded consumption

- **Controls:** monthly plan limits on AI requests and tokens, a daily AI budget, per-conversation message limits, a maximum output token setting, request timeouts and a circuit breaker. Tested in `UsageAnalyticsAndAdminIntegrationTest` and `test_circuit_breaker_stops_calling_a_failing_provider_then_recovers`.

## Log review

Every log statement in the core API, AI service and document worker was read on 2026-11-17.

- None logs message bodies, questions, answers, prompts, document text, email addresses or tokens. The development-only mail setting logs verification links, and is off unless `SERVORA_MAIL_LOG_LINKS=true`.
- **Fixed in M7:** three error logs printed full exception messages and stack traces, and a database exception can quote the row values that broke a constraint. They now log the exception types and the first Servora code location only (`SafeErrors`, `SafeErrorsTest`); the trace id links the line to the request.
- **Fixed in M7:** playground questions were never deleted. The retention job now deletes them after the workspace's retention period, through a function that reads the period itself (migration V8).

## Dependency scanning

Local runs on 2026-11-17, now repeated by the CI job "Secret and dependency scanning" and the Python jobs:

| Tool | Scope | Result |
|---|---|---|
| gitleaks 8.21.2 | Full git history (12 commits) | No leaks |
| Trivy 0.58.1 | Dashboard npm lockfile, core API Maven dependencies; high and critical with a fix | 0 findings |
| pip-audit 2.7.3 | AI service and document worker images | 3 advisories in `setuptools` 78.1.0, pulled in by PyTorch. **Fixed** by pinning `setuptools==84.1.0` in both images; rerun: none |
| pip-audit 2.7.3 | CI test environments of both Python services | None |
| npm audit | Dashboard and widget, production dependencies | None |
| npm audit | Development dependencies | 7 moderate advisories in `typescript-eslint` 8.72.2 and its `minimatch`. The fix, 8.73.0, was released on 2026-11-16. **Accepted for now:** lint tooling never ships, and a one-day-old release is not adopted; revisit on or after 2026-12-01 |

## Not covered by this review

- Saturation and abuse at volume beyond the load test's 418 requests per second.
- TLS and a proxy in front of the production profile.
- A live-model test of prompt injection and answer quality (M7 phase 4).
- An external penetration test.
