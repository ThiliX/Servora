# ADR-013: LLM provider behind an adapter

- **Status:** Accepted (provider chosen by the project owner on 2026-11-17)
- **Date:** 2026-11-16, decided 2026-11-17

## Context

Answer generation needs a hosted LLM. The choice affects cost, structured-output support and which SDK is pinned, but not the architecture.

## Decision

The AI service calls the model through a provider adapter interface (`servora_ai.providers.LlmProvider`) with two implementations:

- **Anthropic Claude**, the one real provider for the MVP, through the official `anthropic` Python SDK (2.9.0). The model is configurable (`SERVORA_LLM_MODEL`) and defaults to `claude-opus-5-5`.
- **A deterministic fake provider**, used by every automated test and selected by default (`SERVORA_LLM_PROVIDER=fake`), so neither the test suite nor a fresh local stack makes a paid call. Real answers need `SERVORA_LLM_PROVIDER=anthropic` and `SERVORA_ANTHROPIC_API_KEY`; the service refuses to start with the real provider and no key.

How the real provider is called:

- **Structured output.** The request passes the answer schema (`answer`, `intent`, `needs_escalation`, `escalation_reason`, `citations`) and the SDK validates the reply against it. A reply that fails validation, is cut off by the token limit, or is empty is a failure path, never passed through.
- **Retries and timeout.** The SDK retries connection errors, 408, 409, 429 and 5xx with backoff (`SERVORA_LLM_MAX_RETRIES`, default 2) under a per-request timeout (`SERVORA_LLM_TIMEOUT_SECONDS`, default 30). The adapter adds no retry loop of its own.
- **Refusals.** The request opts into the API's server-side fallback (`fallbacks: "default"`), so a policy decline is first re-run on the API's default fallback model. A refusal that survives that is treated like any other failure: the customer gets the fallback message and a person.
- **Circuit breaker.** After three consecutive provider failures the service stops calling the provider for 30 seconds and answers with the fallback at once. State is in memory, per process.
- **No sampling or thinking parameters are sent.** Current Claude models reject sampling parameters and manage thinking themselves.

## Consequences

- Each real answer is a paid call. The playground is limited to 60 questions per workspace per hour, and questions with no evidence never reach the model.
- Multi-provider routing is out of scope for the MVP. Adding a provider means one new class implementing `LlmProvider`.
- The provider key lives only in environment or secret storage and is never logged.
- The real provider path is covered by tests against a stubbed SDK client (request shape and error mapping). It has not been exercised against the live API in this repository's automated or recorded runs; see [docs/ai/README.md](../../ai/README.md).

## Revisit when

Cost or latency per answer is measured in M6, or answer quality is evaluated and a cheaper model is shown to hold it.
