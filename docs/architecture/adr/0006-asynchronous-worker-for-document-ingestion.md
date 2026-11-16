# ADR-006: Asynchronous worker for document ingestion

- **Status:** Accepted
- **Date:** 2026-11-16

## Context

Parsing, chunking and embedding documents is slow, resource-heavy and exposed to malformed input. It must not block or endanger HTTP request handling.

## Decision

A Celery worker with Redis as broker performs extraction, normalisation, chunking, embedding and indexing. Tasks are idempotent, retry-safe, time- and memory-bounded. FastAPI `BackgroundTasks` is used only for genuinely short work.

## Consequences

- Uploads return immediately and expose a processing state.
- Parser failures and resource exhaustion are contained in the worker.
- Redis is a broker and cache only, never a source of truth.

## Revisit when

Revisit if job volume needs priorities or a different broker.
