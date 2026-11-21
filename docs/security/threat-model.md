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

| # | Threat | Example | Controls | Milestone | Evidence (reviewed in M7) |
|---|---|---|---|---|---|
| T1 | Cross-tenant access | Tenant A changes an ID to read Tenant B's ticket | Membership checks, tenant-scoped queries, RLS, composite foreign keys | M1–M6 | `CrossTenantMatrixIntegrationTest` (every tenant-scoped endpoint, both through the other workspace's path and with its IDs in the caller's own path, with a check that nothing changed); `TenantIsolationIntegrationTest`; `RowLevelSecurityIntegrationTest`. See [tenant isolation](tenant-isolation.md) |
| T2 | Tenant context leak through the connection pool | A pooled connection keeps a previous request's tenant | Transaction-local `set_config` | M1 | `PooledConnectionIsolationIntegrationTest`; `RowLevelSecurityIntegrationTest` |
| T3 | Broken object-level authorization inside a tenant | An agent opens a ticket assigned to someone else | Object-level checks in every service method | M1–M5 | `RoleEnforcementIntegrationTest`; `TicketWorkflowIntegrationTest.rolesAndOwnershipAreEnforced` |
| T4 | Credential or session theft | Refresh token copied; long-lived token exposed | In-memory access token, rotating HttpOnly refresh cookie, reuse detection, revocation, required client header on refresh | M1 | `AuthFlowIntegrationTest`; dashboard `api.test.ts` |
| T5 | Brute force and credential stuffing | Repeated login attempts from one or many addresses | Three limits in Redis (account and address, account, address); forwarded headers ignored; Argon2id | M1, M7 | `AuthFlowIntegrationTest` (`repeatedFailedSignInsAreRateLimited`, `aForgedForwardedForHeaderDoesNotEscapeTheLimit`); `LoginAttemptLimiterTest` (sliding window, keys stored hashed) |
| T5a | Mass sign-up and email bombing | Scripted registrations; verification emails to one mailbox | Registration limits per address and per mailbox, identical for known and unknown addresses | M7 | `AuthFlowIntegrationTest.signUpsToOneMailboxAreLimitedTheSameWayWhetherOrNotItIsRegistered` |
| T6 | Malicious upload | Oversized or malformed PDF, archive bomb | Extension allow-list, content sniffing, limits, constrained worker, private storage | M2 | `UploadValidatorTest`, `KnowledgeDocumentIntegrationTest`; worker tests. **Not done:** malware scanning |
| T7 | Stale knowledge retrieval | A deleted document still answers questions | Chunks deactivated atomically; retrieval filters on active chunks of ready documents | M2, M3 | `KnowledgeDocumentIntegrationTest`; AI `test_only_active_chunks_of_ready_documents_with_the_same_profile_are_searched` |
| T8 | Prompt injection | Customer says "ignore your rules"; a document contains instructions | Customer text and passages are tagged as untrusted data and escaped; the backend decides escalation and checks citations | M3 | AI `test_injection_in_the_customer_message_stays_inside_its_tag`, `test_injection_inside_a_document_is_escaped_and_cannot_forge_a_citation`. Tested against the fake model; not yet against a live model |
| T9 | Sensitive information disclosure | Model reveals another tenant's document or an internal note | Retrieval under RLS as a read-only role; notes never in the prompt or public queries | M3, M5 | AI `test_each_tenant_is_answered_only_from_its_own_corpus`, `test_the_ai_role_is_read_only_and_sees_nothing_without_a_tenant`; `WidgetConversationIntegrationTest.aTechStoreQuestionCanNeverBeAnsweredFromHotelProKnowledge`; `TicketWorkflowIntegrationTest.notesAndUnsentSuggestionsNeverReachAnyPublicEndpoint` |
| T10 | Fabricated citations | Model invents a chunk ID or cites another tenant's chunk | The core API checks every cited chunk against the tenant and the passages given to the model | M3 | `PlaygroundIntegrationTest.aCitationOfAnotherTenantsChunkIsRejectedAndNothingOfItIsReturned`, `aCitationThatWasNotGivenToTheModelIsRejected` |
| T11 | Excessive agency | Model attempts a refund or account change | No tools; the model can only answer or suggest escalation, which a backend policy decides | M3, M5 | ADR-007; `TicketRulesTest` (escalation policy is pure backend code) |
| T12 | Widget abuse | Public key used for spam or to run up LLM cost | Rate limits by IP, conversation and session; daily AI budget; monthly plan limits | M4, M6 | `WidgetConversationIntegrationTest.messagesAreRateLimitedPerConversation`; `UsageAnalyticsAndAdminIntegrationTest` (plan limits) |
| T13 | Widget token misuse | Customer token calls an admin endpoint or reads another conversation | Separate signing key and audience; token bound to one conversation | M4 | `WidgetConversationIntegrationTest` (`aSessionTokenReadsOnlyItsOwnConversation`, `forgedExpiredAndForeignTokensAreRejected`, `aWidgetTokenIsWorthlessOnDashboardEndpoints`) |
| T14 | Origin spoofing by look-alike domain | `evil-example.com` matching `example.com` | Exact canonical origin matching | M4 | `OriginsTest`; `WidgetConversationIntegrationTest.configIsServedOnlyToAnAllowedOriginWithExactMatch` |
| T15 | LLM output injection | Model returns script or markup | Widget renders with `textContent`; dashboard renders through React; API responses carry `default-src 'none'` | M4, M7 | `widget.test.ts`; `SecurityHeadersIntegrationTest` |
| T16 | AI and human reply race | Late AI answer delivered after takeover | Row lock, version check before delivery | M4, M5 | `TicketWorkflowIntegrationTest.anAiReplyIsNeverDeliveredAfterATakeover` (24 rounds per run) |
| T17 | Internal note exposure | Note returned by a customer endpoint | Notes in their own table; public queries select `PUBLIC` only | M5 | `TicketWorkflowIntegrationTest.notesAndUnsentSuggestionsNeverReachAnyPublicEndpoint` |
| T18 | Data leakage through logs | Exception messages quoting row values; prompts in logs | No message bodies, prompts or emails logged; unexpected errors logged as types and code location only; retention covers conversations and playground questions | All, reviewed in M7 | `SafeErrorsTest`; log review below; `UsageAnalyticsAndAdminIntegrationTest.theRetentionJobDeletesOnlyExpiredConversationsOfThatWorkspace` |
| T19 | Secrets in the repository | Provider key committed | `.env` ignored, placeholders only, gitleaks over the full history in CI | M0, M7 | CI job "Secret and dependency scanning"; local run 2026-11-17: no leaks in 12 commits |
| T20 | Over-privileged platform admin | Routine access to tenant conversations | Separate role that needs a database row and a request flag; no policy on content tables; every action audited | M6 | `UsageAnalyticsAndAdminIntegrationTest` (`theFlagAloneGrantsNoCrossTenantAccess`, `platformEndpointsAreInvisibleToEveryoneElse`) |
| T21 | Vulnerable dependencies and images | Known CVE in a library | Pinned versions; Trivy, pip-audit and npm audit in CI | M7 | Local run 2026-11-17, see [OWASP review](owasp-review.md#dependency-scanning). Image scanning of every production image in the CI job "Production stack": 0 fixable high or critical (M7 phase 3a) |
| T22 | Insecure response headers | API response framed or sniffed as HTML | Strict CSP, `nosniff`, `DENY` framing, no referrer, permissions policy on every API response | M7 | `SecurityHeadersIntegrationTest` |
| T23 | Data leakage through telemetry | Tenant or customer data in metric labels, span attributes or trace headers; metrics endpoint reachable from the internet; a client forging trace IDs or baggage | Metrics only on internal ports (API management port never published or proxied); labels are fixed sets with no tenant, user or document IDs; spans hold counts, scores and statuses only, database spans without parameters, provider HTTP clients not instrumented; gateways strip `traceparent`, `tracestate` and `baggage`; operational gauges from an aggregate-only function | M7 | `ObservabilityIntegrationTest` (metrics only on the management port, no workspace or user IDs in metrics, aggregate-only function); AI service `test_observability.py` (no question or document text in any span, no parameters in database spans); `check-observability.sh` in the CI job "Production stack" (no metrics on the public entry points, forged trace IDs ignored) |

## Additional M1 notes

- **Account enumeration:** registration returns the same response whether or not the email exists, and login returns the same error for an unknown email and a wrong password. Tested in `AuthFlowIntegrationTest`.
- **Secret links:** verification and invitation tokens travel in the URL fragment, which browsers do not send to servers or include in the `Referer` header. Only token hashes are stored.
- **Open redirect:** after sign-in the dashboard returns only to in-app paths.
- **Not yet addressed:** no email provider, so links are logged in development. Response headers, dependency scanning and the `npm audit` triage were done in M7 (see the OWASP review).

## Additional M2 notes

- **Service-to-service authentication:** the worker's enqueue endpoint accepts only a 60-second HS256 token with a fixed issuer and audience, signed with a key separate from the user token key. Wrong key, expired, wrong audience, missing claims and unsigned tokens are rejected (`test_api.py`).
- **Parser safety:** PDFs are parsed with pypdf, a pure-Python library that does not execute document scripts or fetch linked resources. The worker runs as a non-root user with no published ports, a 2 GB memory limit and a 5-minute task limit.
- **Error disclosure:** processing failures store and show a fixed code only; logs record the code or the exception type, never document text.
- **Widget origins:** stored canonically and compared by exact equality. Enforcement at session issue is M4.
- **Not yet addressed:** malware scanning; per-tenant storage credentials; the internal token is not bound to a single use.

## Additional M5 notes

- **AI reply after takeover:** prevented by the version check under a row lock; covered by a repeated race test.
- **Note leakage:** notes are stored apart from messages; suggestions have their own visibility and are filtered out in SQL by every public query; tests assert neither reaches any public response.
- **Object-level access for agents:** every ticket and conversation endpoint checks the agent's assignment and answers `404` otherwise.
- **Duplicate tickets:** escalation is serialised by the conversation row lock and keyed per conversation.
- **Polling load:** the widget checks for replies every five seconds only while a person handles the chat, within a per-conversation rate limit.
- **Not yet addressed:** no email notifications; the escalation word list is English only and not configurable; there is no audit-log entry for ticket actions beyond the ticket's own history.

## Additional M4 notes

- **Public surface:** the widget endpoints are the first ones reachable without an account. They run in a separate security filter chain, accept only a widget key or a widget session token, and take no tenant or conversation id from the request.
- **Embedding by other websites:** the loader's requests carry the website's `Origin`, checked by exact match against the widget's allowed origins, and CORS answers only those origins. A script that forges `Origin` is not stopped by this; it can reach only that widget's public assistant, within the rate limits.
- **Session tokens:** two-hour HS256 tokens with their own signing key, one scope and one conversation. They are stored in the website's `sessionStorage`, readable by that website's scripts, which belong to the workspace owner.
- **Cross-site scripting in the widget:** customer and model text is inserted with `textContent` only, enforced by a lint rule; the iframe page has a restrictive Content Security Policy and no referrer; `postMessage` traffic is checked by source window and origin, and payloads are validated field by field.
- **Abuse and cost:** shared Redis limits per client address (stored hashed), per conversation, and a daily AI-reply budget per workspace. If Redis is down, limited operations are refused.
- **Takeover races:** an AI reply is delivered only if the conversation's mode and version are unchanged under a row lock; otherwise it is stored as suppressed and never shown.
- **Data shown to customers:** citations show document filenames, page numbers, headings and short snippets. Workspace owners should know that filenames are visible to their customers.
- **Not yet addressed:** login rate limiting is still in memory; no CAPTCHA or bot detection on session creation; conversations are never closed automatically; no retention period for conversations yet.

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

Update this file whenever a control is implemented, and record the test that proves it. The review against both OWASP lists is in [owasp-review.md](owasp-review.md) (M7, 2026-11-17).
