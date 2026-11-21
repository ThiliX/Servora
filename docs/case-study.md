# Case study: building Servora

A short write-up of the problem, the main design decisions and what was measured. The full reasoning for each decision is in the [architecture decision records](architecture/adr/README.md).

## The problem

Small businesses want an AI assistant on their website that answers customers' questions, but two things stop them trusting one: it might make things up, and it might mix up their data with someone else's. Servora is a platform where many businesses each get their own assistant, answering only from their own documents, with a clean handover to a person when it should not answer.

That sets the bar: **an answer must be traceable to the business's own documents, one business must never see another's data, and handing over to a person must be reliable.**

## Key decisions

**A modular monolith plus two Python services, not microservices.** The core API (Spring Boot) owns identity, tenancy, permissions, conversations and tickets. AI work lives in a separate Python service because the ML ecosystem is there, and document processing runs in a background worker because parsing a large PDF should never block a request. Three deployable parts, each for a concrete reason ([ADR-001](architecture/adr/0001-modular-monolith-core-with-a-separate-python-ai-service.md)).

**Tenant isolation in the database, not only in the code.** Every tenant-owned table has a `tenant_id`, foreign keys include it, and PostgreSQL Row-Level Security is forced on every table. The application connects as roles that own nothing and cannot bypass the policies, so a forgotten `WHERE` clause returns nothing instead of another tenant's data. A test matrix calls every endpoint as the wrong tenant, and another hammers a two-connection pool from parallel tenants to prove that settings never leak between pooled connections.

**The AI may only cite what it was given.** The AI service retrieves the tenant's passages, refuses to call the model when the best evidence is too weak, and asks the model for a structured answer whose citations must be among the retrieved passages. The core API checks the citations again. A provider failure, a malformed reply or an uncited answer becomes the business's fallback message and a handover, never a guess shown to a customer.

**Takeover without races.** When a person takes over, the conversation's state changes in the database with optimistic locking. An AI reply that was being generated at that moment is checked against that state when it finishes and is dropped if a person is now in charge. A test runs this race 24 times on every build.

**Reliable background work.** Uploading a document writes the document and an outbox event in one transaction; a dispatcher hands the job to the worker and retries with backoff. The trace context travels in the outbox row, so one trace shows the upload, the handover and every processing stage seconds later.

## What was measured

| | Result |
|---|---|
| Answer quality, live model (Gemini 3.5 Flash-Lite), 50 questions | 87% of answerable questions answered; every answer from the right document; 0 of 10 unanswerable questions answered; every request for a person handed over ([report](ai/evaluation-report.md)) |
| Retrieval baseline, 40 questions | Recall@5 0.97, MRR 0.90 ([baseline](ai/evaluation-baseline.md)) |
| Cost | About $0.0008 per conversation at the model's paid price |
| API latency (non-AI endpoints) | P95 15 ms at 100 virtual users on one development machine ([load test](testing/load-test.md)) |
| Images | 0 fixable high or critical vulnerabilities in all five container images |
| Accessibility | No serious or critical WCAG 2.1 AA findings on 13 dashboard pages and the chat |

The evaluation also showed where the system is weakest: questions phrased very differently from the documents ("stay longer" against "late check-out") fall below the evidence threshold and are handed to a person. That is the safe failure, and it points at the next improvement.

## Things that went wrong, and what they taught

- **A fixed similarity threshold cannot separate answerable from unanswerable questions.** The retrieval baseline showed the scores overlap, so the design relies on several layers (threshold, a prompt that tells the model to decline, citation checks), not one.
- **Traces joined the wrong requests.** The Java HTTP client opened connections with an HTTP/2 upgrade request that the Python server does not support, after which it dropped later requests' trace headers. Found by looking at real traces, not by the tests; fixed by using HTTP/1.1 for internal calls and covered by a test.
- **A test passed locally and failed in CI** because an earlier test left a database result open, which kept a tracing span open on the test thread. Test order differs between machines; the fix closed the resources and isolated the test.
- **The first accessibility scan failed**: the chat log broke list semantics and could not be scrolled by keyboard, and some text was below the contrast minimum. Automated checks now run on every build.

## Built with

Java 25, Spring Boot 4, PostgreSQL 18 with pgvector, Redis, Python 3.13 with FastAPI and Celery, sentence-transformers, React 19 with TypeScript, Playwright, k6, Docker Compose, OpenTelemetry, Prometheus, Jaeger and GitHub Actions.

## Limits

Servora is a portfolio project, not a hosted product. The evaluation uses 50 questions written by one person, and its groundedness review was done by an AI assistant, not a person. Email delivery is not built, the assistant does not yet use earlier messages in a conversation, and there is no public deployment. The full list is in the [README](../README.md#limitations).
