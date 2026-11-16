# ADR-010: Outbox plus an internal enqueue endpoint for Java-to-Celery dispatch

- **Status:** Accepted (recommended in M0)
- **Date:** 2026-11-16

## Context

The core API (Java) must schedule Celery tasks (Python). Publishing Celery's wire format from Java is brittle, and a publish that is not atomic with the database write can lose or duplicate jobs.

## Decision

When a document is accepted, the core API writes an `outbox_events` row in the same transaction as the document record. A dispatcher in the core API delivers each event to an authenticated internal enqueue endpoint on the Python side, which calls the Celery task. Delivery is at-least-once; tasks are idempotent on document id, version and content hash.

## Consequences

- No job is lost if the broker or Python service is briefly unavailable.
- Celery's message format stays an internal Python concern.
- Duplicate deliveries are expected and must be harmless.

## Revisit when

Revisit if event volume makes polling the outbox a bottleneck.
