"""Celery application and the ingestion task."""

import logging
from functools import lru_cache
from uuid import UUID

import psycopg
from celery import Celery
from celery.exceptions import Retry, SoftTimeLimitExceeded

from .config import get_settings
from .embeddings import Embedder, SentenceTransformerEmbedder
from .errors import TransientError
from .pipeline import mark_failed, process_document
from .storage import DocumentStore, S3DocumentStore

log = logging.getLogger(__name__)

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
)


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
def ingest_document(self, tenant_id: str, document_id: str) -> str:
    """Arguments come from the enqueue endpoint, which takes them only from a verified token."""
    tenant, document = UUID(tenant_id), UUID(document_id)
    try:
        return process_document(_connect, _store(), _embedder(), _settings, tenant, document)
    except TransientError as error:
        if self.request.retries >= self.max_retries:
            mark_failed(_connect, tenant, document, "PROCESSING_UNAVAILABLE")
            return "failed"
        raise self.retry(exc=error, countdown=min(2 ** (self.request.retries + 1) * 5, 120)) from error
    except Retry:
        raise
    except SoftTimeLimitExceeded:
        mark_failed(_connect, tenant, document, "PROCESSING_TIMEOUT")
        return "failed"
    except Exception as error:
        # Never leave a document stuck in PROCESSING. Only the exception type is logged: its message
        # could contain document text.
        log.error("ingestion failed unexpectedly: %s", type(error).__name__)
        mark_failed(_connect, tenant, document, "INTERNAL_ERROR")
        return "failed"


def enqueue_ingestion(tenant_id: UUID, document_id: UUID) -> None:
    ingest_document.delay(str(tenant_id), str(document_id))
