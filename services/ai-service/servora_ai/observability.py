"""Logs, traces and metrics setup (ADR-022). The document worker has an identical module.

- Logs: one line per event, as text in development or JSON (``SERVORA_LOG_FORMAT=json``) in the
  production profile. Every line carries the current ``trace_id`` and ``span_id``, so it can be
  matched with the core API's log lines and the trace of the same request.
- Traces: an OpenTelemetry tracer that continues the trace context the core API sends
  (``traceparent``). Spans are exported over OTLP only when ``OTEL_EXPORTER_OTLP_TRACES_ENDPOINT``
  is set; otherwise they exist only to put trace IDs in the logs.
- Metrics: Prometheus counters and histograms, served by the service itself on ``/metrics``.

Nothing here records a question, a prompt, document text, a token or a key.
"""

import json
import logging
import os
import sys
from datetime import UTC, datetime

from opentelemetry import trace
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.sdk.trace.sampling import ParentBased, TraceIdRatioBased

_configured = False


class _TraceContextFilter(logging.Filter):
    """Adds the current trace and span IDs to every record ("" outside a span)."""

    def filter(self, record: logging.LogRecord) -> bool:
        context = trace.get_current_span().get_span_context()
        record.trace_id = format(context.trace_id, "032x") if context.is_valid else ""
        record.span_id = format(context.span_id, "016x") if context.is_valid else ""
        return True


class JsonFormatter(logging.Formatter):
    """One JSON object per line, with the same field names as the core API's trace fields."""

    def __init__(self, service: str) -> None:
        super().__init__()
        self.service = service

    def format(self, record: logging.LogRecord) -> str:
        entry = {
            "@timestamp": datetime.fromtimestamp(record.created, UTC).isoformat(timespec="milliseconds"),
            "log.level": record.levelname,
            "service.name": self.service,
            "log.logger": record.name,
            "message": record.getMessage(),
            "trace_id": getattr(record, "trace_id", ""),
            "span_id": getattr(record, "span_id", ""),
        }
        if record.exc_info and record.exc_info[0] is not None:
            # The exception type only: messages and stack traces can contain document text.
            entry["error.type"] = record.exc_info[0].__name__
        return json.dumps(entry, ensure_ascii=False)


def configure_logging(service: str, level: int = logging.INFO) -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.addFilter(_TraceContextFilter())
    if os.environ.get("SERVORA_LOG_FORMAT", "text").lower() == "json":
        handler.setFormatter(JsonFormatter(service))
    else:
        handler.setFormatter(
            logging.Formatter(
                "%(asctime)s %(levelname)5s [trace_id=%(trace_id)s span_id=%(span_id)s] %(name)s: %(message)s"
            )
        )
    root = logging.getLogger()
    for existing in list(root.handlers):
        root.removeHandler(existing)
    root.addHandler(handler)
    root.setLevel(level)
    # Uvicorn's own loggers propagate to the root handler instead of printing separately.
    for name in ("uvicorn", "uvicorn.error", "uvicorn.access", "celery"):
        logger = logging.getLogger(name)
        logger.handlers.clear()
        logger.propagate = True


def configure_tracing(service: str) -> None:
    """Installs the tracer provider once per process."""
    global _configured
    if _configured:
        return
    _configured = True
    ratio = float(os.environ.get("SERVORA_TRACE_SAMPLING", "1.0"))
    provider = TracerProvider(
        resource=Resource.create({"service.name": service}),
        # A request the core API decided to keep is always kept here too.
        sampler=ParentBased(TraceIdRatioBased(ratio)),
    )
    if os.environ.get("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT") or os.environ.get("OTEL_EXPORTER_OTLP_ENDPOINT"):
        from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter

        provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter()))
    trace.set_tracer_provider(provider)


def instrument_psycopg() -> None:
    """Spans for database statements: the SQL with its placeholders, never the parameters."""
    from opentelemetry.instrumentation.psycopg import PsycopgInstrumentor

    instrumentor = PsycopgInstrumentor()
    if not instrumentor.is_instrumented_by_opentelemetry:
        instrumentor.instrument(enable_commenter=False)
