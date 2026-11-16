# ADR-007: Retrieval-augmented generation only, no autonomous consequential actions

- **Status:** Accepted
- **Date:** 2026-11-16

## Context

A support assistant that invents policy or acts on a customer's account can cause real harm. Customer messages and retrieved documents are untrusted and may contain prompt injection.

## Decision

The AI answers from retrieved, tenant-approved evidence with verified citations, classifies, suggests, and may request a ticket or escalation through one narrow allowlisted backend operation. It cannot issue refunds, change orders or accounts, or perform any other consequential action. When evidence is weak or conflicting it asks for clarification or hands off to a human.

## Consequences

- Prompt injection cannot reach privileged tools, because none exist.
- Every backend action requested by the model still passes backend policy checks.
- Some answerable questions will be escalated; that is the accepted trade-off.

## Revisit when

Revisit only with a separate design and threat model for each proposed action.
