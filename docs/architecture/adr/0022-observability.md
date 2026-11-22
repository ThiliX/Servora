# ADR-022: Observability: logs, metrics and traces

- **Status:** Accepted (decided in M7)
- **Date:** 2026-11-21

## Context

The requirements ask for structured logs, health and readiness endpoints, metrics, and OpenTelemetry traces "from browser through core API and AI service to the provider and database" where practical. It also asks for latency, failure categories, retrieval statistics, tokens, usage limits, queue age and document processing state, and that secrets and unnecessary personal data are never recorded.

Before M7 every API request already had a correlation ID (`X-Request-Id`, the `trace_id` in error bodies and log lines), but it stopped at the core API. The AI service and the worker logged plain text with nothing linking a line to the request that caused it. There were no metrics apart from health.

## Decision

**One ID per request, everywhere.** The request ID is the OpenTelemetry trace ID. The core API starts a span for every request (Micrometer Tracing with the OpenTelemetry bridge) and returns its trace ID in `X-Request-Id` and in error bodies. Calls to the AI service and the worker carry the W3C `traceparent` header, and both continue the trace (OpenTelemetry for Python). A support report that quotes the ID leads to the trace and to every log line of every service that handled the request.

**Across the queue.** Uploads are handed to the worker later, by the outbox dispatcher. The outbox row stores the uploading request's `traceparent` (new column, format-checked), and the dispatcher continues that trace when it delivers the event. The worker's enqueue endpoint passes it into the Celery job's headers. One trace shows the upload, the hand-over, and the download, extract, chunk, embed and index stages.

**Traces start at the API.** The public gateways remove `traceparent`, `tracestate` and `baggage` from incoming requests, so a client cannot choose trace IDs, force sampling or put values into log fields through baggage. Browser spans are therefore not part of the trace: the browser sees the trace ID in `X-Request-Id` and error bodies, which is enough to find the trace. Inside the network, services continue each other's traces.

**What is not traced.** Health probes, metric scrapes and the outbox's two-second poll start no trace. Database statements are traced only inside another operation. Span attributes are identifiers, counts, scores, status values and timings: never a question, a prompt, an answer, document text, a token or a key. Database spans hold the SQL with its placeholders, never the parameters. The model provider call is a manual span with the provider, model, token counts and result; the HTTP client libraries the provider SDKs use are not instrumented, because their URLs and headers can carry API keys.

**Export is optional.** Spans are always created, so their IDs are in the logs. They are exported over OTLP only when an endpoint is configured (`MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` for the API, `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` for the Python services). Sampling keeps every trace by default (`SERVORA_TRACE_SAMPLING`, 0 to 1); downstream services follow the API's decision.

**Metrics: Prometheus, scraped, on internal ports only.** The core API serves `/actuator/prometheus` and health details on a separate management port (8081), which is never published or proxied. The API port keeps only `/livez` and `/readyz` for container health checks. The AI service and the worker API serve `/metrics` on their internal ports; the Celery worker's job processes write to a shared directory and its main process serves the combined values on port 9100. Labels are fixed sets (outcome, source, provider, status, limiter family, stage); no workspace, user, conversation or document identifier is ever a label. Platform-wide gauges (documents by status, outbox backlog and age, oldest document still processing, open tickets) come from `operational_metrics()`, a `SECURITY DEFINER` function that returns aggregates only, refreshed every 30 seconds.

**Structured logs in production.** The production profile writes one JSON object per line: the API in the Elastic Common Schema (Spring Boot structured logging), the Python services with the same trace fields. Development keeps readable text lines with the same fields. Log lines written inside a workspace's transaction carry the workspace ID; it is removed when the transaction ends.

**Optional tooling.** `compose.observability.yml` adds Prometheus, with alert rules, and Jaeger, both bound to `127.0.0.1`. Neither is part of the production profile itself: a deployment chooses its own metrics and tracing backends.

## Consequences

- Every request has one ID that works across services, in logs, traces and error bodies.
- The core API calls the internal services over HTTP/1.1. Java's HTTP client otherwise opens each connection with an HTTP/2 upgrade request; uvicorn does not support HTTP/2 and then lost the trace header of every later request on that connection, so those requests joined the wrong trace. Found in the production-profile run and covered by `AiClientTracePropagationTest`.
- A client-supplied `X-Request-Id` is no longer echoed when tracing is on; the ID is always the server's trace ID.
- The management port must stay internal. If a deployment maps it to the outside, metrics and health details become public.
- Browser-side spans are not collected.
- The Jaeger configuration keeps traces in memory only. Alert routing (Alertmanager, email or chat) is left to each deployment.
- Prometheus and Jaeger images are not scanned in CI, since they are optional and not part of the production profile.
