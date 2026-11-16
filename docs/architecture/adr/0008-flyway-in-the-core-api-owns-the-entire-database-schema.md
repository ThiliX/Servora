# ADR-008: Flyway in the core API owns the entire database schema

- **Status:** Accepted (recommended in M0)
- **Date:** 2026-11-16

## Context

Three services touch PostgreSQL. Two migration tools would create ordering and ownership conflicts, particularly around RLS policies and grants.

## Decision

All schema objects, including `document_chunks`, the pgvector extension, roles' grants and RLS policies, are created by Flyway migrations in `services/core-api`. Python services ship no migrations and treat the schema as a contract.

## Consequences

- One ordered migration history and one place to review tenant isolation.
- Python changes that need schema changes require a core API migration first.
- Hibernate schema auto-update stays disabled.

## Revisit when

Revisit if the AI service acquires data that the core API has no reason to know about.
