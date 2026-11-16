# ADR-013: LLM provider behind an adapter

- **Status:** Proposed (provider not chosen)
- **Date:** 2026-11-16

## Context

Answer generation needs a hosted LLM. The choice affects cost, structured-output support and which SDK is pinned, but not the architecture.

## Decision

The AI service calls the model through a provider adapter interface. A deterministic fake provider is used for all automated tests, so the test suite never makes paid calls. Exactly one real provider is implemented for the MVP. **Which provider is undecided and must be chosen by the project owner before M3 starts.**

## Consequences

- M0 to M2 are unaffected by the choice.
- Multi-provider routing is out of scope for the MVP.
- The provider's key lives only in environment or secret storage.

## Revisit when

Decide before M3; record the provider, model and SDK version in the version matrix.
