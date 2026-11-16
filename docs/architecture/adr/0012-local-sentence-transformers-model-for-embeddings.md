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
- M2 chose `sentence-transformers/all-MiniLM-L6-v2` at revision `1110a243fdf4706b3f48f1d95db1a4f5529b4d41` (384 dimensions, normalised). It is baked into the worker image, which is about 1.7 GB with CPU-only PyTorch. Its quality is unmeasured until the M3 evaluation.

## Revisit when

Revisit if retrieval quality on the evaluation set is inadequate.
