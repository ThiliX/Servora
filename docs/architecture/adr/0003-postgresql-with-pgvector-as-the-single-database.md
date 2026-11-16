# ADR-003: PostgreSQL with pgvector as the single database

- **Status:** Accepted
- **Date:** 2026-11-16

## Context

The product needs relational data and vector similarity search. A separate vector store would add a second system to secure, back up and keep consistent.

## Decision

Use PostgreSQL 18 with the pgvector extension for both relational and vector data. Start with exact similarity search plus tenant filters and ordinary B-tree indexes. Do not add HNSW or IVFFlat until a benchmark shows the need and filtered recall has been measured.

## Consequences

- Chunks get the same RLS and tenant-aware keys as every other table.
- One backup and restore procedure covers everything.
- A vector index is never treated as an isolation boundary.

## Revisit when

Revisit when exact search latency exceeds the target on a realistic corpus.
