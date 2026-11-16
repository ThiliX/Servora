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
| T6 | Malicious upload | Oversized or malformed PDF, archive bomb | Extension allowlist, content sniffing, byte/page/text/time limits, constrained worker, private storage, no fetching of embedded URLs | M2 | — |
| T7 | Stale knowledge retrieval | A deleted document still answers questions | Deactivate chunks on replace/delete in one transaction; retrieval filters on active state | M2, M3 | — |
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
