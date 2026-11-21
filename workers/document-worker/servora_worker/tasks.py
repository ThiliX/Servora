"""Celery application and the ingestion task."""

import logging
import os
import time
from functools import lru_cache
from uuid import UUID

import psycopg
from celery import Celery, signals
from celery.exceptions import Retry, SoftTimeLimitExceeded
from opentelemetry import trace
from opentelemetry.instrumentation.celery import CeleryInstrumentor

from . import metrics
from .config import get_settings
from .embeddings import Embedder, SentenceTransformerEmbedder
from .errors import TransientError
from .observability import configure_logging, configure_tracing, instrument_psycopg
from .pipeline import mark_failed, process_document
from .storage import DocumentStore, S3DocumentStore

log = logging.getLogger(__name__)

SERVICE_NAME = "servora-document-worker"
# The default Celery queue. The enqueue API reports its length as a metric.
QUEUE_NAME = "celery"

_settings = get_settings()

celery_app = Celery("servora_worker", broker=_settings.redis_url)
celery_app.conf.update(
    task_serializer="json",
    accept_content=["json"],
    # Results are never read: the outcome is the document's status in the database.
    task_ignore_result=True,
    # Acknowledge only after the task finishes, so a crashed worker's job is redelivered.
    task_acks_late=True,
    task_reject_on_worker_lost=True,
    worker_prefetch_multiplier=1,
    # One parsed document at a time per process, and recycle processes to release parser memory.
    worker_max_tasks_per_child=50,
    task_soft_time_limit=_settings.task_soft_time_limit_seconds,
    task_time_limit=_settings.task_time_limit_seconds,
    broker_connection_retry_on_startup=True,
    # Our log format, with trace IDs, instead of Celery's.
    worker_hijack_root_logger=False,
)


@signals.setup_logging.connect
def _setup_logging(**_kwargs) -> None:
    # Connecting this signal stops Celery from configuring logging itself.
    configure_logging(SERVICE_NAME)


@signals.worker_init.connect
def _serve_metrics(**_kwargs) -> None:
    """In the main worker process: serve the combined metrics of every job process."""
    metrics.serve_worker_metrics(int(os.environ.get("SERVORA_WORKER_METRICS_PORT", "9100")))


@signals.worker_process_init.connect
def _instrument_process(**_kwargs) -> None:
    """In each job process: trace jobs, continuing the enqueue request's trace, and their queries."""
    configure_tracing(SERVICE_NAME)
    CeleryInstrumentor().instrument()
    instrument_psycopg()


def instrument_publisher() -> None:
    """In the enqueue API: put the current trace context in each job's headers when publishing."""
    instrumentor = CeleryInstrumentor()
    if not instrumentor.is_instrumented_by_opentelemetry:
        instrumentor.instrument()


@lru_cache
def _embedder() -> Embedder:
    return SentenceTransformerEmbedder(
        _settings.embedding_model,
        _settings.embedding_revision,
        _settings.embedding_dimension,
        _settings.embedding_batch_size,
    )


@lru_cache
def _store() -> DocumentStore:
    return S3DocumentStore(_settings)


def _connect() -> psycopg.Connection:
    return psycopg.connect(_settings.postgres_conninfo)


@celery_app.task(name="servora.ingest_document", bind=True, max_retries=_settings.task_max_retries)
def ingest_document(self, tenant_id: str, document_id: str, enqueued_at: float | None = None) -> str:
    """Arguments come from the enqueue endpoint, which takes them only from a verified token.

    ``enqueued_at`` (Unix time) measures how long the job waited. Jobs queued by an older version
    do not have it.
    """
    tenant, document = UUID(tenant_id), UUID(document_id)
    if enqueued_at is not None and self.request.retries == 0:
        metrics.QUEUE_WAIT_SECONDS.observe(max(0.0, time.time() - enqueued_at))
    started = time.monotonic()
    code = ""
    try:
        result = process_document(_connect, _store(), _embedder(), _settings, tenant, document)
    except TransientError as error:
        if self.request.retries >= self.max_retries:
            mark_failed(_connect, tenant, document, "PROCESSING_UNAVAILABLE")
            result, code = "failed", "PROCESSING_UNAVAILABLE"
        else:
            _count("retry", "TRANSIENT", started)
            raise self.retry(exc=error, countdown=min(2 ** (self.request.retries + 1) * 5, 120)) from error
    except Retry:
        raise
    except SoftTimeLimitExceeded:
        mark_failed(_connect, tenant, document, "PROCESSING_TIMEOUT")
        result, code = "failed", "PROCESSING_TIMEOUT"
    except Exception as error:
        # Never leave a document stuck in PROCESSING. Only the exception type is logged: its message
        # could contain document text.
        log.error("ingestion failed unexpectedly: %s", type(error).__name__)
        mark_failed(_connect, tenant, document, "INTERNAL_ERROR")
        result, code = "failed", "INTERNAL_ERROR"
    trace.get_current_span().set_attribute("servora.document.result", result)
    _count(result, code, started)
    return result


def _count(result: str, code: str, started: float) -> None:
    metrics.DOCUMENTS.labels(result, code).inc()
    metrics.DOCUMENT_SECONDS.labels(result).observe(time.monotonic() - started)


def enqueue_ingestion(tenant_id: UUID, document_id: UUID) -> None:
    ingest_document.delay(str(tenant_id), str(document_id), enqueued_at=time.time())
