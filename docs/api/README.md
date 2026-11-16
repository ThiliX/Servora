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

## Idempotency

- Registration, verification and invitation acceptance are safe to retry: a repeat either does nothing or returns a clear conflict (`INVITATION_NOT_PENDING`, `ALREADY_MEMBER`).
- Creating a duplicate pending invitation returns `409 INVITATION_EXISTS`.
- Idempotency keys for message submission and ticket creation arrive with those endpoints in M4 and M5.

## Planned endpoint groups

| Group | Base path | Milestone |
|---|---|---|
| Assistant | `/workspaces/{workspaceId}/assistant` | M2, M3 |
| Knowledge | `/workspaces/{workspaceId}/knowledge` | M2 |
| Conversations | `/workspaces/{workspaceId}/conversations` | M4, M5 |
| Tickets | `/workspaces/{workspaceId}/tickets` | M5 |
| Analytics | `/workspaces/{workspaceId}/analytics` | M6 |
| Public widget | `/public` | M4 |
