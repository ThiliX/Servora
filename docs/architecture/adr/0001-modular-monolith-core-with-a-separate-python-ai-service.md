# ADR-001: Modular monolith core with a separate Python AI service

- **Status:** Accepted
- **Date:** 2026-11-16

## Context

Servora needs identity, tenancy, conversations, tickets and analytics, plus AI retrieval and generation. It is built by one developer and must stay deployable with Docker Compose.

## Decision

The core business application is one Spring Boot modular monolith, organised package-by-feature. AI runtime work lives in a separate Python FastAPI service, and heavy document processing in a Celery worker. No further services are introduced without a demonstrated need.

## Consequences

- One deployable core keeps transactions and authorization simple.
- Python is used only where its ML ecosystem is needed.
- Module boundaries inside the monolith rely on discipline and review rather than network isolation.

## Revisit when

Revisit if team size or load makes independent scaling of a core module necessary.
