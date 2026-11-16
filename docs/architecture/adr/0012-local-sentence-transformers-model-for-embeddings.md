# ADR-012: Local Sentence Transformers model for embeddings

- **Status:** Accepted (recommended in M0)
- **Date:** 2026-11-16

## Context

Embeddings are needed for every chunk and every query. A hosted embedding API adds cost, a network dependency in tests, and sends tenant documents to a third party.

## Decision

Generate embeddings locally with a Sentence Transformers model. The model identifier, revision and vector dimension are stored with each chunk and form an embedding profile. Queries are embedded with the same profile, and retrieval refuses to compare across profiles. Changing the model means a new profile and a re-index.

## Consequences

- No per-token embedding cost and deterministic tests.
- The worker image is larger and CPU embedding is slower than a hosted API.
- The specific model is chosen in M2 and evaluated in M3 against the labelled set.

## Revisit when

Revisit if retrieval quality on the evaluation set is inadequate.
