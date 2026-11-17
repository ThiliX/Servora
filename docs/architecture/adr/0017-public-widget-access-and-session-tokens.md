# ADR-017: Public widget access, origins and session tokens

- **Status:** Accepted (decided in M4)
- **Date:** 2026-11-17

## Context

The chat widget runs on customers' websites for anonymous visitors. It must reach exactly one workspace's assistant, only from that workspace's own websites, and a visitor must never be able to read another conversation or touch a dashboard endpoint. The widget is an iframe served from Servora's own origin ([ADR-005](0005-iframe-widget-mounted-by-a-small-loader-script.md)), so requests made from inside the iframe carry Servora's origin, not the customer's website.

## Decision

**Two kinds of public request, with different credentials.**

1. *Widget key requests* (`/api/v1/public/widgets/{key}/config` and `/sessions`) are made by the loader script on the customer's website, so the browser sets the website's `Origin` header. The key is resolved and the origin must match one of the widget's allowed origins exactly, after canonicalisation. CORS allows that origin only.
2. *Conversation requests* (`/api/v1/public/conversation/**`) are made from the iframe with a session token. CORS allows only the origin that serves the iframe (`SERVORA_WIDGET_APP_ORIGIN`).

The loader passes the session token to the iframe with `postMessage`, addressed to the iframe's origin, and checks that replies come from that iframe.

**Key resolution before a tenant is known.** A transaction-local setting, `app.widget_public_key`, set only by `TenantContext`, lets the transaction read the one widget row with that key. The widget's tenant is then bound from that row, and every other read happens under that tenant, as in [ADR-015](0015-row-level-security-context-beyond-the-tenant-id.md). Unknown, malformed, rotated and disabled keys all return the same `404`.

**Session tokens.** HS256 JWTs signed with their own key (`SERVORA_WIDGET_TOKEN_SECRET`), separate from the dashboard key, so a widget token cannot pass the dashboard's token check even by mistake. Claims: issuer, audience `servora-widget`, a single scope, tenant, widget, customer (subject) and conversation, issued-at and a two-hour expiry. The public endpoints take no tenant, workspace or conversation id from the request; the conversation comes from the token only. Public endpoints run in their own Spring Security filter chain that has no dashboard token processing at all.

**One session, one conversation.** Starting a session creates an anonymous customer and a conversation. There is no token refresh: when a token expires, the widget starts a new conversation and says so.

**Shared rate limits in Redis.** Fixed windows per client address (hashed), per conversation, and a daily AI-reply budget per tenant. If Redis is unreachable, limited operations are refused rather than left unbounded.

## Consequences

- The origin check stops other websites from embedding a widget in a browser. It does not stop a script that forges the `Origin` header; rate limits and the tenant budget bound what such a client can do, and it can only ever reach that widget's public assistant.
- The widget key is public by design. Rotating it stops old embeds immediately.
- The loader's host page can read the session token. That page belongs to the workspace owner, and the token grants only that one conversation.
- A visitor who clears session storage or switches browsers starts a new conversation; there is no visitor identity in the MVP.
- Each new deployment needs a fourth secret.

## Revisit when

Visitors need to resume conversations across devices, or the widget needs authenticated end users.
