# API documentation

The REST API is versioned under `/api/v1`. The core API generates its OpenAPI description from the code:

- Specification: <http://localhost:8080/v3/api-docs>
- Interactive reference: <http://localhost:8080/swagger-ui.html>

Both are served only by a running core API. JSON field names are `snake_case`.

## Contract rules

- Every endpoint documents its authentication, tenant resolution, required permission, request and response schema, validation rules, error codes and idempotency behaviour.
- List endpoints are paginated with `page` (from 0) and `size` (1 to 100).
- Path IDs are validated against the authenticated tenant context on every operation.
- A workspace the caller cannot access returns the same `404` as one that does not exist.
- Internal notes are never exposed through public endpoints.

## Authentication

- `POST /auth/login` returns a short-lived access token in the body and sets a rotating refresh token in an `HttpOnly`, `Secure`, `SameSite=Strict` cookie scoped to `/api/v1/auth`.
- Send the access token as `Authorization: Bearer <token>`.
- `POST /auth/refresh` and `POST /auth/logout` are authenticated by the cookie and must carry the header `X-Servora-Client`. A cross-site page cannot add that header without a CORS preflight.
- Each refresh token works once. Presenting a used token revokes the whole session.

## Error envelope

```json
{
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "message": "The requested resource was not found.",
    "trace_id": "<correlation-id>"
  }
}
```

`trace_id` matches the `X-Request-Id` response header and the server logs. Validation errors add a `details` array of `{field, message}`.

## Endpoints implemented in M1

Paths are relative to `/api/v1`. "Member" means any active member of the workspace.

| Method | Path | Access | Purpose |
|---|---|---|---|
| POST | `/auth/register` | Public | Create a user and send a verification link. Always `202` |
| POST | `/auth/verify-email` | Public | Activate the account with the emailed token |
| POST | `/auth/login` | Public, rate limited | Start a session |
| POST | `/auth/refresh` | Refresh cookie | Rotate the session |
| POST | `/auth/logout` | Refresh cookie | Revoke the session |
| GET | `/auth/me` | Signed in | Current user |
| POST | `/workspaces` | Signed in | Create a workspace; the caller becomes its owner |
| GET | `/workspaces` | Signed in | Workspaces where the caller is an active member |
| GET | `/workspaces/{workspaceId}` | Member | Read a workspace and the caller's role in it |
| PATCH | `/workspaces/{workspaceId}` | Owner, Admin | Update name, timezone, language, industry, logo URL |
| GET | `/workspaces/{workspaceId}/members` | Member | List members |
| PATCH | `/workspaces/{workspaceId}/members/{memberId}` | Owner, Admin | Change role or status. Only an owner may change an owner or grant ownership; nobody may change themselves |
| POST | `/workspaces/{workspaceId}/invitations` | Owner, Admin | Invite by email with a role other than owner |
| GET | `/workspaces/{workspaceId}/invitations` | Owner, Admin | List invitations |
| DELETE | `/workspaces/{workspaceId}/invitations/{invitationId}` | Owner, Admin | Revoke a pending invitation |
| POST | `/invitations/accept` | Signed in, invited email only | Join the workspace with the emailed token |
| GET | `/workspaces/{workspaceId}/audit-events` | Owner, Admin | Audit log, newest first |

Outside `/api/v1`: `GET /actuator/health`, `/actuator/health/liveness` and `/actuator/health/readiness` are public.

## Endpoints implemented in M2

| Method | Path | Access | Purpose |
|---|---|---|---|
| GET | `/workspaces/{workspaceId}/assistant` | Owner, Admin | Read the assistant configuration |
| PUT | `/workspaces/{workspaceId}/assistant` | Owner, Admin | Replace the configuration; records a new immutable version |
| GET | `/workspaces/{workspaceId}/assistant/versions` | Owner, Admin | Version history, newest first |
| GET | `/workspaces/{workspaceId}/widget` | Owner, Admin | Widget settings, public key and allowed origins |
| PATCH | `/workspaces/{workspaceId}/widget` | Owner, Admin | Change title, colour, position, branding, status |
| POST | `/workspaces/{workspaceId}/widget/rotate-key` | Owner, Admin | Issue a new public key; the old one stops resolving |
| PUT | `/workspaces/{workspaceId}/widget/allowed-origins` | Owner, Admin | Replace the allowed-origin list (at most 20) |
| POST | `/workspaces/{workspaceId}/knowledge/documents` | Owner, Admin | Upload a document (`multipart/form-data`, part `file`). Returns `202` |
| GET | `/workspaces/{workspaceId}/knowledge/documents` | Owner, Admin | List documents |
| GET | `/workspaces/{workspaceId}/knowledge/documents/{documentId}` | Owner, Admin | Read one document and its processing state |
| POST | `/workspaces/{workspaceId}/knowledge/documents/{documentId}/replace` | Owner, Admin | Upload a new version. Returns `202` |
| POST | `/workspaces/{workspaceId}/knowledge/documents/{documentId}/archive` | Owner, Admin | Take the document out of retrieval |
| POST | `/workspaces/{workspaceId}/knowledge/documents/{documentId}/reprocess` | Owner, Admin | Process again (retry a failure or restore an archived document). Returns `202` |
| DELETE | `/workspaces/{workspaceId}/knowledge/documents/{documentId}` | Owner, Admin | Delete the document, its chunks and its stored file |

Upload rules: PDF, TXT or Markdown; 10 MB by default; the content must match the extension. Errors: `UNSUPPORTED_FILE_TYPE` (415), `CONTENT_MISMATCH` and `EMPTY_FILE` (400), `FILE_TOO_LARGE` (413), `DUPLICATE_DOCUMENT` and `DOCUMENT_LIMIT_REACHED` (409), `UPLOAD_RATE_LIMITED` (429). Lifecycle errors: `DOCUMENT_BUSY` and `NOT_LATEST_VERSION` (409).

Allowed origins must be plain origins such as `https://shop.example.com`: no path, query, wildcard or credentials, and https except for localhost. The server stores and returns the canonical form.

## Endpoints implemented in M3

| Method | Path | Roles | Purpose |
|---|---|---|---|
| POST | `/workspaces/{workspaceId}/assistant/test` | Owner, Admin | Ask the assistant a question privately. Body: `{"question": "..."}`, at most 2,000 characters |

The response always has status `200` when the question was accepted, whatever the assistant did with it. `outcome` says what happened:

| Outcome | Meaning | `answer` contains |
|---|---|---|
| `ANSWERED` | Answered from the knowledge base, with at least one verified citation | The model's reply |
| `ESCALATED` | The model replied but asked for a person (for example conflicting sources) | The model's reply |
| `NO_EVIDENCE` | Nothing matched well enough; the model was not called | The fallback message |
| `PROVIDER_UNAVAILABLE` | The model timed out, was rate limited or is failing | The fallback message |
| `INVALID_OUTPUT`, `INVALID_CITATION`, `UNGROUNDED`, `REFUSED` | The model's reply was unusable, cited something it was not given, cited nothing, or declined | The fallback message |
| `SERVICE_UNAVAILABLE` | The AI service could not be reached | The fallback message |

Other fields: `id`, `intent`, `needs_escalation`, `escalation_reason`, `citations` (`chunk_id`, `document_id`, `filename`, `page_number`, `heading`, `snippet`), `retrieval` (every retrieved chunk with its `score` and whether it was `used`), `provider`, `model`, `prompt_version`, `evidence_threshold`, `latency_ms`, `input_tokens`, `output_tokens`.

Errors: `VALIDATION_FAILED` (400) for an empty or over-long question, `PLAYGROUND_RATE_LIMITED` (429) after 60 questions per workspace per hour.

## Endpoints implemented in M4

### Public widget

No dashboard token is accepted here. The widget key names the widget; after that, the session token names the conversation. See [ADR-017](../architecture/adr/0017-public-widget-access-and-session-tokens.md).

| Method | Path | Credential | Purpose |
|---|---|---|---|
| GET | `/public/widgets/{key}/config` | Website `Origin` | Title, colour, position, branding and welcome message |
| POST | `/public/widgets/{key}/sessions` | Website `Origin` | Start an anonymous conversation. Returns `token`, `expires_at`, `conversation_id`, `config` |
| GET | `/public/conversation` | Session token | The conversation's status, control mode and public messages (latest 200) |
| POST | `/public/conversation/messages` | Session token, `Idempotency-Key` | Send a message. Body: `{"text": "..."}`, at most 2,000 characters. The response is an event stream ([ADR-014](../architecture/adr/0014-server-sent-events-from-the-core-api-for-customer-streaming.md)) |
| POST | `/public/conversation/handoff` | Session token | Ask for a person. Stops AI replies; repeating it changes nothing |

Errors: `WIDGET_NOT_FOUND` (404, for unknown, rotated and disabled keys alike), `ORIGIN_NOT_ALLOWED` (403), `WIDGET_SESSION_INVALID` (401), `IDEMPOTENCY_KEY_REQUIRED` (400), `IDEMPOTENCY_KEY_REUSED` and `CONVERSATION_CLOSED` (409), `WIDGET_RATE_LIMITED` (429), `SERVICE_UNAVAILABLE` (503, when the rate-limit store is down).

Message events: `customer_message`, `assistant_typing`, `assistant_message` (with `citations`: `filename`, `page_number`, `heading`, `snippet`), `notice` (`code`: `HUMAN_PENDING`, `AUTOMATION_OFF`, `SUPPRESSED`, `REPLY_PENDING`), `error`, `done`.

Default limits: config 120 per address per minute; sessions 30 per address per hour; messages 10 per conversation and 30 per address per minute; 2,000 AI replies per workspace per day, after which customers get the fallback message.

### Conversations (dashboard)

| Method | Path | Roles | Purpose |
|---|---|---|---|
| GET | `/workspaces/{workspaceId}/conversations` | Owner, Admin, Supervisor | Conversations with at least one message, latest first. Paged |
| GET | `/workspaces/{workspaceId}/conversations/{conversationId}` | Owner, Admin, Supervisor | One conversation's summary |
| GET | `/workspaces/{workspaceId}/conversations/{conversationId}/messages` | Owner, Admin, Supervisor | Every message, oldest first, including internal and suppressed ones, with citations and the AI outcome. Paged |

## Endpoints implemented in M5

Design: [ADR-018](../architecture/adr/0018-human-handoff-escalation-and-takeover.md). "Workers" means the ticket's assignee, or an owner, admin or supervisor.

| Method | Path | Roles | Purpose |
|---|---|---|---|
| GET | `/workspaces/{workspaceId}/tickets?view=ALL\|MINE\|UNASSIGNED&state=ACTIVE\|DONE` | All; agents see only their own and unassigned | Ticket queue, by priority then age. Paged |
| GET | `/workspaces/{workspaceId}/tickets/{ticketId}` | As above | Ticket, conversation state and version, history, customer rating, and whether the caller may work on it |
| GET | `/workspaces/{workspaceId}/tickets/assignees` | Owner, Admin, Supervisor | Active members a ticket can be assigned to |
| PUT | `/workspaces/{workspaceId}/tickets/{ticketId}/assignee` | Owner, Admin, Supervisor | `{"membership_id": "..."}` or `null`. Notifies the new assignee |
| PATCH | `/workspaces/{workspaceId}/tickets/{ticketId}` | Workers | `{"status": "IN_PROGRESS" \| "WAITING_FOR_CUSTOMER"}` and/or `{"priority": ...}` |
| POST | `/workspaces/{workspaceId}/tickets/{ticketId}/resolve` | Workers | `{"outcome": "SOLVED" \| "NOT_SOLVED" \| "NO_REPLY_FROM_CUSTOMER" \| "DUPLICATE", "note": "..."}`. Ends the customer's conversation |
| POST | `/workspaces/{workspaceId}/tickets/{ticketId}/close` | Workers | Resolved to closed |
| POST | `/workspaces/{workspaceId}/conversations/{conversationId}/takeover` | Assignee, or anyone for an unassigned ticket; owners, admins and supervisors also for unescalated conversations | `{"expected_version": n}`. Refused with `409 CONVERSATION_CHANGED` if the version moved |
| POST | `/workspaces/{workspaceId}/conversations/{conversationId}/replies` | The assignee, after takeover | `{"body": "...", "suggestion_id": "..."}`. Otherwise `409 TAKEOVER_REQUIRED` |
| GET, POST | `/workspaces/{workspaceId}/conversations/{conversationId}/notes` | Anyone who can see the conversation | Internal notes. Never returned by any public endpoint |
| POST | `/workspaces/{workspaceId}/conversations/{conversationId}/suggestions` | Anyone who can see the conversation | AI draft of a reply to the latest customer message. Stored, not sent |
| GET | `/workspaces/{workspaceId}/notifications` | All | The caller's latest 50 notifications and unread count |
| POST | `/workspaces/{workspaceId}/notifications/read` | All | `{"ids": [...]}`, or empty to mark all read |
| POST | `/public/conversation/feedback` | Session token | `{"rating": 1-5, "comment": "..."}`, once, after the conversation is resolved |

Public widget changes: message streams may include a `system_message` event; the history includes `feedback_submitted`; handoff now creates a ticket; and the history is limited to 60 requests per conversation per minute. Errors: `TICKET_NOT_FOUND` (404), `NOT_ASSIGNED` and `TAKEOVER_NOT_ALLOWED` (403), `CONVERSATION_CHANGED`, `TAKEOVER_REQUIRED`, `TICKET_NOT_ACTIVE`, `TICKET_NOT_RESOLVED`, `INVALID_TRANSITION`, `CONVERSATION_NOT_RESOLVED`, `FEEDBACK_EXISTS` (409), `INVALID_STATUS`, `INVALID_ASSIGNEE` (400).

## Idempotency

- Registration, verification and invitation acceptance are safe to retry: a repeat either does nothing or returns a clear conflict (`INVITATION_NOT_PENDING`, `ALREADY_MEMBER`).
- Creating a duplicate pending invitation returns `409 INVITATION_EXISTS`.
- Uploading content identical to a live document returns `409 DUPLICATE_DOCUMENT`, so a retried upload does not create a second copy.
- Archive, reprocess and delete can be repeated safely; a repeat returns the current state or a clear conflict.
- Customer messages require an `Idempotency-Key` header. A retry with the same key and text stores nothing new and replays the stored message and reply; the same key with different text returns `409 IDEMPOTENCY_KEY_REUSED`.
- Escalation is idempotent per conversation: repeated or parallel requests create one ticket and one notice.
- A takeover repeated by the person who already holds the conversation returns the current state.
- A customer rating is accepted once; a repeat returns `409 FEEDBACK_EXISTS`.

## Planned endpoint groups

| Group | Base path | Milestone |
|---|---|---|
| Analytics | `/workspaces/{workspaceId}/analytics` | M6 |
