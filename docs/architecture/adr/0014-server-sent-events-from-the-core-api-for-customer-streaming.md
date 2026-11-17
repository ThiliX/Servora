# ADR-014: Server-Sent Events from the core API for customer streaming

- **Status:** Accepted (recommended in M0, amended in M4)
- **Date:** 2026-11-16, amended 2026-11-17

## Context

Customers should see progress as an answer is produced, but a generated answer must not reach the customer after a human agent has taken over, and no answer may be shown before its citations are checked.

## Decision

The widget receives Server-Sent Events from the core API. The widget never connects to the AI service.

Sending a message is a single `POST`; its response is the event stream:

| Event | Meaning |
|---|---|
| `customer_message` | The customer's message as stored |
| `assistant_typing` | An AI reply is being prepared |
| `assistant_message` | The verified reply, with its citations |
| `notice` | No AI reply will follow, with a code: `HUMAN_PENDING`, `AUTOMATION_OFF`, `SUPPRESSED` or `REPLY_PENDING` |
| `error` | The reply failed (`REPLY_FAILED`) |
| `done` | The stream is complete |

Before an AI reply is stored as delivered, the core API locks the conversation row and checks that it is still open, still `AI_ACTIVE`, and at the same version as when the reply was requested. Otherwise the reply is stored as `INTERNAL` and `SUPPRESSED`, the widget receives `notice: SUPPRESSED`, and the customer never sees the text.

**Amendment (M4): no token-by-token streaming.** The answer is shown only after it is complete and its citations have been verified, so the stream carries progress and the final message rather than partial text. Streaming partial text would show customers words that might then fail verification or be withdrawn by a takeover. The widget therefore never has to retract text it has already shown.

The widget reads the stream with `fetch` rather than `EventSource`, because `EventSource` cannot send the session token in a header.

## Consequences

- The takeover check stays in the system of record.
- SSE is one-directional and simpler to secure than WebSockets for this use.
- Customers wait for the whole answer. With the fake provider this is milliseconds; with a real model, several seconds behind a typing indicator.
- If the customer disconnects, the reply is still generated and stored, and appears in the history when the widget is reopened.

## Revisit when

Answer latency with the real model is measured and found too long, or agents and customers need a bidirectional real-time channel (M5).
