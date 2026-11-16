# ADR-014: Server-Sent Events from the core API for customer streaming

- **Status:** Accepted (recommended in M0)
- **Date:** 2026-11-16

## Context

Customers should see an answer as it is generated, but a generated answer must not reach the customer after a human agent has taken over.

## Decision

The widget receives streamed responses over Server-Sent Events from the core API. The core API proxies the AI service stream, and before the final message is persisted and marked delivered it re-reads the conversation in a transaction and verifies the control mode and version. The widget never connects to the AI service.

## Consequences

- The takeover check stays in the system of record.
- SSE is one-directional and simpler to secure than WebSockets for this use.
- Partially streamed text can be withdrawn by a suppression event; M4 defines how the widget renders that.

## Revisit when

Revisit if agents and customers need a bidirectional real-time channel.
