# Threat model

Started in M0 from the implementation handbook and updated as controls are built. It lists the threats, the planned controls and the milestone where each control is implemented and tested. The "Evidence" column is filled in only when a test exists and has been run. Nothing here is a claim that the system is secure.

Review checklists: [OWASP API Security Top 10](https://owasp.org/www-project-api-security/) and [OWASP Top 10 for LLM Applications](https://owasp.org/www-project-top-10-for-large-language-model-applications/).

## Assets

- Tenant data: knowledge documents and chunks, conversations, messages, internal notes, tickets, analytics, usage.
- User identities, password hashes, sessions and refresh tokens.
- Signing secrets, service-to-service credentials, the LLM provider key.
- LLM spend and platform availability.

## Trust boundaries

| Boundary | Untrusted side | Rule |
|---|---|---|
| Browser → core API | Dashboard user input, workspace IDs in URLs | Authenticate, then verify membership and role for the requested workspace |
| Host website → widget → core API | Any visitor, any origin | Public key is an identifier only; issue short-lived scoped session tokens; rate limit |
| Core API → AI service | — | Signed, short-lived internal token carrying validated tenant and conversation |
| AI service → LLM | Customer text and retrieved documents | Treated as data; cannot change system policy or authorise actions |
| LLM → core API | Model output | Schema-validated; citations verified; no privileged action without backend policy checks |
| Upload → worker | File contents | Allowlist, content sniffing, limits, constrained execution |

## Threats and controls

| # | Threat | Example | Planned controls | Milestone | Evidence |
|---|---|---|---|---|---|
| T1 | Cross-tenant access | Tenant A changes an ID to read Tenant B's ticket | Membership checks, tenant-scoped queries, RLS, composite foreign keys, negative tests per resource family | M1, extended in M2–M6 | M1 resources: `TenantIsolationIntegrationTest`, `RowLevelSecurityIntegrationTest`. See [tenant isolation](tenant-isolation.md) |
| T2 | Tenant context leak through the connection pool | A pooled connection keeps a previous request's tenant | Transaction-local `set_config`; parallel pooled-connection test | M1 | `PooledConnectionIsolationIntegrationTest`; `RowLevelSecurityIntegrationTest` (context cleared after commit and rollback) |
| T3 | Broken object-level authorization | Agent requests another tenant's conversation ID | Object-level authorization in every service method; integration tests | M1–M5 | M1 resources: ID-substitution cases in `TenantIsolationIntegrationTest`; `RoleEnforcementIntegrationTest` |
| T4 | Credential or session theft | Refresh token copied; long-lived token exposed | In-memory access token, rotating HttpOnly refresh cookie, reuse detection, revocation, CSRF handling on refresh | M1 | `AuthFlowIntegrationTest` (rotation, replay revokes family, cookie attributes, required client header, logout); dashboard `api.test.ts` (token never in web storage, single shared refresh) |
| T5 | Brute force and credential stuffing | Repeated login attempts | Login rate limiting, adaptive password hashing | M1 | `AuthFlowIntegrationTest`, `LoginAttemptLimiterTest`. Limiter is per instance and in memory; Argon2id via Spring Security |
| T6 | Malicious upload | Oversized or malformed PDF, archive bomb | Extension allowlist, content sniffing, byte/page/text/time limits, constrained worker, private storage, no fetching of embedded URLs | M2 | `UploadValidatorTest`, `KnowledgeDocumentIntegrationTest` (extension allow-list, content sniffing, size, empty, private generated keys); worker `test_extract_and_chunking.py` and `test_pipeline.py` (page and text limits, encrypted and malformed PDFs, safe error codes); container memory, CPU and task time limits. **Not done:** malware scanning |
| T7 | Stale knowledge retrieval | A deleted document still answers questions | Deactivate chunks on replace/delete in one transaction; retrieval filters on active state | M2, M3 | M2: archive deactivates chunks, delete removes them, a new version retires the old one atomically (`KnowledgeDocumentIntegrationTest`, `test_pipeline.py`). Retrieval-side filter arrives in M3 |
| T8 | Prompt injection | Customer says "ignore your rules"; a document contains instructions to reveal data | Retrieved text labelled as untrusted; backend policy is authoritative; adversarial tests for message and document injection | M3 | — |
| T9 | Sensitive information disclosure | Model reveals another customer's message or an internal note | Minimal context, tenant filter, visibility rules, output validation | M3, M5 | — |
| T10 | Fabricated citations | Model invents a chunk ID | Verify every citation against the retrieved set for that tenant | M3 | — |
| T11 | Excessive agency | Model attempts a refund or account change | No consequential tools in the MVP; one allowlisted escalation operation behind backend checks | M3, M5 | — |
| T12 | Widget abuse | Public key used for spam or to run up LLM cost | Rate limits by widget, IP, session and tenant budget; short-lived scoped tokens; quotas | M4, M6 | — |
| T13 | Widget token misuse | Customer token calls an admin endpoint or reads another conversation | Token bound to tenant, session, conversation, expiry and allowed actions | M4 | — |
| T14 | Origin spoofing by look-alike domain | `evil-example.com` matching `example.com` | Exact normalised origin matching; origin checks treated as supplementary, not as authentication | M4 | — |
| T15 | LLM output injection | Model returns script or malicious markup | Render as text or structured components; sanitise | M4 | — |
| T16 | AI and human reply race | Late AI answer delivered after takeover | Atomic takeover, optimistic locking, pre-delivery re-check, concurrency test | M4, M5 | — |
| T17 | Internal note exposure | Note returned by a customer endpoint | Visibility filter in the query; negative tests on every public endpoint | M5 | — |
| T18 | Data leakage through logs | Full prompts and personal data stored indefinitely | Minimal logging, redaction, retention policy | All, reviewed in M7 | — |
| T19 | Secrets in the repository | Provider key committed | `.env` ignored, `.env.example` placeholders only, diff review, scanning in CI | M0, M7 | `.gitignore` in place; runtime role password kept out of migrations; scanning not yet added |
| T20 | Over-privileged platform admin | Routine access to tenant conversations | Separate platform role; explicit, audited, narrow access | M6 | — |
| T21 | Vulnerable dependencies and images | Known CVE in a library | Pinned versions, dependency and image scanning in CI | M7 | — |

## Additional M1 notes

- **Account enumeration:** registration returns the same response whether or not the email exists, and login returns the same error for an unknown email and a wrong password. Tested in `AuthFlowIntegrationTest`.
- **Secret links:** verification and invitation tokens travel in the URL fragment, which browsers do not send to servers or include in the `Referer` header. Only token hashes are stored.
- **Open redirect:** after sign-in the dashboard returns only to in-app paths.
- **Not yet addressed:** no email provider, so links are logged in development; no review of security response headers; no dependency scanning. `npm audit` reports moderate advisories in the dashboard's dependency tree, to be triaged in M7.

## Additional M2 notes

- **Service-to-service authentication:** the worker's enqueue endpoint accepts only a 60-second HS256 token with a fixed issuer and audience, signed with a key separate from the user token key. Wrong key, expired, wrong audience, missing claims and unsigned tokens are rejected (`test_api.py`).
- **Parser safety:** PDFs are parsed with pypdf, a pure-Python library that does not execute document scripts or fetch linked resources. The worker runs as a non-root user with no published ports, a 2 GB memory limit and a 5-minute task limit.
- **Error disclosure:** processing failures store and show a fixed code only; logs record the code or the exception type, never document text.
- **Widget origins:** stored canonically and compared by exact equality. Enforcement at session issue is M4.
- **Not yet addressed:** malware scanning; per-tenant storage credentials; the internal token is not bound to a single use.

## Additional M3 notes

- **Prompt injection:** retrieved text and the customer's message are escaped and wrapped in labelled tags, and the system prompt tells the model to treat them as content, not instructions. This lowers the risk; it does not remove it. The controls that do not depend on the model behaving are: the model has no tools and no access to other tenants' data, its output must match a fixed schema, and every citation is checked against what was retrieved. Tests cover the escaping and the citation check with a fake model; behaviour of the real model under attack is unmeasured.
- **Hallucinated answers:** no evidence means no model call. An answer that cites nothing, or cites something it was not given, is replaced by the fallback message.
- **Untrusted AI service:** the core API re-verifies every chunk id in a reply against the caller's tenant before returning it.
- **Provider failure:** timeout, bounded retry, and a circuit breaker; every failure path returns the fallback message and asks for a person. Provider error details are logged as a short code and never returned.
- **Cost abuse:** the playground is limited to owners and admins and to 60 questions per workspace per hour (in memory). The default provider is a fake that costs nothing.
- **Data sent to the provider:** with the real provider, the customer's question and the retrieved passages leave the system for Anthropic's API. Tenants must be told this before M4 exposes the assistant to customers.
- **Logging:** the AI service logs tenant id, outcome, counts and latency. It does not log questions, prompts or document text. Questions and answers are stored in `ai_interactions` under Row-Level Security; retention is decided in M7.
- **Not yet addressed:** the provider key is a plain environment variable; no per-tenant spend cap.

## File upload checklist (M2)

- Allow only PDF, TXT and Markdown; determine the real content type, not just the extension.
- Limit file bytes, page count, extracted text length and processing time.
- Store under generated object keys in private storage.
- Run parsers in a constrained worker.
- Do not follow remote URLs embedded in documents.
- Record processing errors without stack traces or extracted content.
- Exclude deleted and archived documents from retrieval and cache.

## Privacy baseline

Store only the customer information needed to provide support. Define retention and deletion before any real customer data is used. Do not log full conversation bodies or prompts by default. Provide tenant data export and deletion as scoped in M6. This document is an engineering design, not legal advice.

## Review schedule

Update this file whenever a control is implemented, and record the test that proves it. A full review against both OWASP lists happens in M7.
