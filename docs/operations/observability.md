# Observability

Logs, metrics and traces for every Servora service. The design and its reasons are in [ADR-022](../architecture/adr/0022-observability.md).

## Follow one request

Every API response carries `X-Request-Id`, and every error body repeats it as `trace_id`. It is the request's trace ID, the same in all services:

1. **Logs.** Search for the ID. The core API's lines show it as `trace_id=…` in development and as `traceId` in the production profile's JSON. The AI service and worker lines show it as `trace_id` in both.
2. **Trace.** With the observability overlay running, open Jaeger (<http://localhost:16686>) and search by trace ID. An answered question shows the API request, the call to the AI service, its retrieval and database queries, and the model call. An upload shows the API request, the outbox delivery seconds later, the worker's enqueue request, and the job's download, extract, chunk, embed and index stages.

The public gateways drop any `traceparent` a client sends, so the trace always starts at the API.

## Start the tools

Development, with the core API on the host:

```bash
docker compose -f docker-compose.yml -f compose.observability.yml up -d
cd services/core-api
MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT=http://localhost:4318/v1/traces ./mvnw spring-boot:run
```

Production profile:

```bash
docker compose -p servora-prod -f docker-compose.yml -f compose.prod.yml \
  -f compose.observability.yml -f compose.observability.prod.yml up -d --build --wait
```

| Tool | Address | Notes |
|---|---|---|
| Prometheus | <http://localhost:9090> | Scrapes every service every 15 s and evaluates the alert rules. Data kept 15 days |
| Jaeger | <http://localhost:16686> | Traces in memory: lost when the container restarts |

Both bind to 127.0.0.1 and have no authentication. Without the overlay nothing is exported: trace IDs still appear in logs, and metrics are still served on the internal network.

## Where metrics are served

| Service | Address (internal network) | Notes |
|---|---|---|
| Core API | `:8081/actuator/prometheus` | Management port. Never published by the production profile or proxied by the gateway |
| AI service | `:8091/metrics` | |
| Worker API | `:8090/metrics` | Includes the ingestion queue's depth |
| Document worker | `:9100/metrics` | Combined values of all job processes |

The API port itself serves only `/livez` and `/readyz`.

## Metrics

No metric has a workspace, user, conversation or document label. Per-workspace figures are on the Usage and Overview pages, which read the database.

| Metric | Labels | Meaning |
|---|---|---|
| `http_server_requests_seconds` | `method`, `uri` (template), `status`, `outcome` | Every API request |
| `servora_internal_requests_seconds` | `target`, `status` | Calls from the API to the AI service and the worker |
| `servora_ai_answers_seconds` (API) | `source`, `outcome`, `provider` | Questions answered, from the playground, the widget or agent suggestions; time as measured by the AI service |
| `servora_ai_tokens_total` (API) | `direction`, `provider` | Tokens reported by the provider |
| `servora_ai_retrieval_chunks` | `stage` (`retrieved`, `used`) | Chunks found per question, and how many passed the evidence gate |
| `servora_plan_limit_reached_total` | `limit` (`ai_requests`, `ai_tokens`, `conversations`, `documents`) | Requests refused by a plan limit |
| `servora_rate_limit_rejections_total` | `limiter`, `reason` (`limited`, `unavailable`) | Requests refused by a rate limit; `unavailable` means Redis could not be reached |
| `servora_outbox_delivery_seconds` | `event_type`, `result` | Outbox deliveries: `delivered`, `retry` or `failed` |
| `servora_documents` | `status` | Documents by status, all workspaces |
| `servora_documents_processing_oldest_age_seconds` | | How long the oldest document has been processing |
| `servora_outbox_events` | `status` (`PENDING`, `FAILED`) | Undelivered and abandoned outbox events |
| `servora_outbox_oldest_pending_age_seconds` | | Age of the oldest undelivered outbox event |
| `servora_tickets_open` | `status` | Tickets not yet closed |
| `servora_ai_answers_total`, `servora_ai_answer_seconds` (AI service) | `outcome` | Answers by outcome, with time from retrieval to verification |
| `servora_ai_retrieval_seconds` | | Embedding the question and searching |
| `servora_ai_retrieved_chunks` | `stage` | As above, measured in the AI service |
| `servora_ai_top_score` | | Similarity of the best chunk per question; useful for tuning the evidence threshold |
| `servora_ai_provider_seconds` | `provider`, `result` (`ok`, `unavailable`, `malformed`, `refused`) | Time waiting for the model provider |
| `servora_ai_provider_circuit_open` | | 1 while the circuit breaker skips the provider |
| `servora_worker_jobs_enqueued_total` | `result` | Jobs accepted by the worker's enqueue endpoint, or refused because the broker was down |
| `servora_worker_queue_depth` | | Jobs waiting in the ingestion queue |
| `servora_worker_queue_wait_seconds` | | Time a job waited before a worker started it |
| `servora_worker_documents_total` | `result`, `code` | Documents processed: `ready`, `failed` (with the failure code), `superseded`, `skipped`, `retry` |
| `servora_worker_document_seconds` | `result` | Time to process one document |
| `servora_worker_stage_seconds` | `stage` (`download`, `extract`, `chunk`, `embed`, `index`) | Time per processing stage |

The operational gauges (`servora_documents`, `servora_outbox_*`, `servora_tickets_open`) are refreshed every 30 seconds from `operational_metrics()`, a database function that returns totals only.

## Alerts

Rules are in [infrastructure/monitoring/alerts.yml](../../infrastructure/monitoring/alerts.yml). Prometheus evaluates them and shows them under Alerts; sending them to people needs an Alertmanager, which each deployment configures. The thresholds are starting points.

| Alert | First things to check |
|---|---|
| `ServiceDown` | `docker compose ps` and the service's logs. For the core API, also the migrations container |
| `ApiServerErrors` | Core API log lines at `ERROR`; take a `trace_id` and open its trace |
| `OutboxBacklog`, `OutboxEventsFailed` | The worker API's health and logs, and Redis. Failed events have marked their documents `FAILED` with `DISPATCH_FAILED`; re-upload them |
| `DocumentStuckProcessing` | The document worker's logs and memory. A job past its time limit is marked `PROCESSING_TIMEOUT` |
| `IngestionQueueGrowing` | Whether the document worker is running; consider more worker processes |
| `ModelProviderUnavailable`, `ProviderCircuitOpen` | The AI service's `provider unavailable reason=…` log lines: timeouts, rate limits or a bad key. Customers are handed to a person meanwhile |
| `RateLimiterUnavailable` | Redis. Rate-limited endpoints refuse requests (503) until it is back |

## Logs

| Service | Development | Production profile |
|---|---|---|
| Core API | Text, with `[trace_id=… span_id=… tenant_id=…]` | JSON (Elastic Common Schema): `traceId`, `spanId`, `tenant_id` |
| AI service, worker API, document worker | Text, with `[trace_id=… span_id=…]` | JSON: `trace_id`, `span_id`, `service.name` |

`tenant_id` is the workspace bound to the database transaction when the line was written; it is empty outside one.

What is never logged or recorded in a span: questions, prompts, answers, document text, passwords, tokens, API keys, email verification or invitation links (except with `SERVORA_MAIL_LOG_LINKS=true`, which is for local development and CI only), and exception messages, which can quote data. Errors log the exception type and the first Servora code location.

## Settings

| Variable | Service | Default | Meaning |
|---|---|---|---|
| `SERVORA_MANAGEMENT_PORT` | Core API | `8081` | Port for health details and metrics |
| `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` | Core API | unset | OTLP/HTTP traces endpoint, such as `http://jaeger:4318/v1/traces`. Unset: no export |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | Python services | unset | The same, for the AI service and worker |
| `SERVORA_TRACE_SAMPLING` | All | `1.0` | Share of new traces kept, 0 to 1. Downstream services follow the API's decision |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | Core API | unset | `ecs` for JSON lines (set by the production profile) |
| `SERVORA_LOG_FORMAT` | Python services | `text` | `json` for JSON lines (set by the production profile) |
| `SERVORA_WORKER_METRICS_PORT` | Document worker | `9100` | Port for the worker's metrics |
| `PROMETHEUS_MULTIPROC_DIR` | Document worker | `/tmp/prometheus` in Compose | Where job processes write their metric values |

## Checking a running production profile

```bash
COMPOSE="docker compose -p servora-prod -f docker-compose.yml -f compose.prod.yml" \
  bash infrastructure/ci/check-observability.sh
```

It checks that every service serves metrics on the internal network, that neither public entry point serves metrics or accepts a client's trace ID, and that log lines are JSON with trace IDs. CI runs it after the browser tests.
