# ADR-018: Human handoff: escalation, takeover and agent replies

- **Status:** Accepted (decided in M5)
- **Date:** 2026-11-17

## Context

When the assistant cannot or should not answer, a person must take the conversation, and the customer must never receive an AI reply after that person has started. Escalations can be triggered from several places at once (the customer's button, the AI reply path, an agent), and must not create duplicate tickets.

## Decision

**Escalation.** A pure policy decides when to escalate: customer request, conflicting sources, a sensitive request reported by the model, two unusable replies in a row, a service error, or a built-in list of legal, fraud and data-protection terms in the customer's message. One unusable reply only offers a person. Sentiment is not used. Escalation locks the conversation row, creates the ticket with an idempotency key derived from the conversation (`conversation:<id>`), moves the conversation to `ESCALATION_PENDING` and adds a system notice, all in one transaction. Ticket numbers come from a per-workspace counter on the tenant row, which also serialises ticket creation per workspace.

**Two state machines.** The conversation's control mode (`AI_ACTIVE`, `ESCALATION_PENDING`, `HUMAN_ACTIVE`) says who may reply. The ticket's status (`OPEN`, `IN_PROGRESS`, `WAITING_FOR_CUSTOMER`, `RESOLVED`, `CLOSED`) tracks the work. Resolving the ticket closes the conversation, after which the customer may rate it once.

**Takeover.** The caller sends the conversation version it last saw. Under the row lock, a different version is refused with `CONVERSATION_CHANGED`; otherwise the conversation becomes `HUMAN_ACTIVE`, the version increases, and the taker becomes the assignee. An AI reply being generated at that moment fails the pre-delivery check of [ADR-014](0014-server-sent-events-from-the-core-api-for-customer-streaming.md) and is stored as suppressed. Only the assignee may reply, and only while `HUMAN_ACTIVE`.

**Visibility.** Internal notes live in their own table, so no query for customer-facing messages can return one. AI suggestions are stored as messages with visibility `AI_SUGGESTION`; sending one creates a separate agent message that references it. Public endpoints select `visibility = 'PUBLIC'` in SQL.

**Who sees what.** Owners, admins and supervisors see every conversation and assign tickets. Agents see tickets assigned to them and unassigned tickets they may pick up by taking over; anything else is reported as not found.

**Delivery to the customer.** The widget checks the conversation history every five seconds while a person is handling the chat. There is no push channel.

## Consequences

- Takeover races are decided by the database row lock, and the integration suite runs the race repeatedly in both orders.
- A five-second delay before the customer sees an agent's reply, and one history request per open escalated chat every five seconds, within a per-conversation rate limit.
- The risk word list is English only and not yet configurable per workspace.
- A conversation has at most one ticket; a returning customer starts a new conversation.

## Revisit when

Agents need typing indicators or instant delivery (push or WebSockets), or workspaces need their own escalation rules.
