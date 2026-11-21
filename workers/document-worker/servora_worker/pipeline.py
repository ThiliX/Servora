"""The ingestion pipeline: claim, download, extract, chunk, embed, index.

Tenant isolation: every transaction first sets ``app.tenant_id`` transaction-locally, and every
statement also filters by tenant. The worker connects with its own unprivileged database role, so
Row-Level Security applies to everything it does.

Idempotency: a document's chunks are replaced as a whole inside one transaction together with the
status change. Running the job twice, or re-running it after a crash, leaves exactly one set of
chunks.
"""

import logging
import time
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from uuid import UUID, uuid4

import psycopg
from opentelemetry import trace

from . import metrics
from .chunking import Chunk, chunk_sections
from .config import Settings
from .embeddings import Embedder
from .errors import PermanentError, TransientError
from .extract import extract
from .storage import DocumentStore

log = logging.getLogger(__name__)
tracer = trace.get_tracer("servora_worker.pipeline")


@contextmanager
def _stage(name: str) -> Iterator[trace.Span]:
    """A span and a timing for one processing stage. Attributes are counts, never content."""
    started = time.monotonic()
    with tracer.start_as_current_span(name) as span:
        try:
            yield span
        finally:
            metrics.STAGE_SECONDS.labels(name).observe(time.monotonic() - started)


ConnectionFactory = Callable[[], psycopg.Connection]

_CLAIMABLE = ("UPLOADED", "QUEUED", "PROCESSING")


@dataclass(frozen=True)
class _Claimed:
    storage_key: str
    mime: str
    lineage_id: UUID
    version: int


def process_document(
    connect: ConnectionFactory,
    store: DocumentStore,
    embedder: Embedder,
    settings: Settings,
    tenant_id: UUID,
    document_id: UUID,
) -> str:
    """Processes one document and returns the outcome: ready, failed, skipped or superseded."""
    claimed = _claim(connect, tenant_id, document_id)
    if claimed is None:
        return "skipped"
    try:
        with _stage("download") as span:
            data = store.get(claimed.storage_key)
            span.set_attribute("servora.document.bytes", len(data))
        with _stage("extract") as span:
            sections = extract(
                claimed.mime,
                data,
                max_pdf_pages=settings.max_pdf_pages,
                max_text_chars=settings.max_text_chars,
            )
            span.set_attribute("servora.document.mime", claimed.mime)
            span.set_attribute("servora.document.sections", len(sections))
        with _stage("chunk") as span:
            chunks = chunk_sections(
                sections, max_chars=settings.chunk_max_chars, overlap_chars=settings.chunk_overlap_chars
            )
            span.set_attribute("servora.document.chunks", len(chunks))
        if not chunks:
            raise PermanentError("EMPTY_DOCUMENT")
        if len(chunks) > settings.max_chunks:
            raise PermanentError("TOO_MANY_CHUNKS")
        with _stage("embed"):
            vectors = embedder.embed([chunk.text for chunk in chunks])
    except PermanentError as error:
        # Only the code is recorded. No content, file name or stack trace.
        log.warning("document failed: code=%s", error.code)
        _mark_failed(connect, tenant_id, document_id, error.code)
        return "failed"

    with _stage("index"):
        indexed = _index(connect, embedder, tenant_id, document_id, claimed, chunks, vectors)
    return "ready" if indexed else "superseded"


def mark_failed(connect: ConnectionFactory, tenant_id: UUID, document_id: UUID, code: str) -> None:
    """Records a failure from outside the pipeline, for example after retries are exhausted."""
    _mark_failed(connect, tenant_id, document_id, code)


def _set_tenant(cursor: psycopg.Cursor, tenant_id: UUID) -> None:
    cursor.execute("SELECT set_config('app.tenant_id', %s, true)", (str(tenant_id),))


def _claim(connect: ConnectionFactory, tenant_id: UUID, document_id: UUID) -> _Claimed | None:
    try:
        with connect() as connection, connection.transaction(), connection.cursor() as cursor:
            _set_tenant(cursor, tenant_id)
            cursor.execute(
                """
                SELECT storage_key, detected_mime, lineage_id, version, status
                FROM documents
                WHERE tenant_id = %s AND id = %s
                FOR UPDATE
                """,
                (tenant_id, document_id),
            )
            row = cursor.fetchone()
            # Archived or deleted since the job was queued, or already finished: nothing to do.
            if row is None or row[4] not in _CLAIMABLE or row[0] is None:
                return None
            cursor.execute(
                "UPDATE documents SET status = 'PROCESSING', error_code = NULL, updated_at = now() "
                "WHERE tenant_id = %s AND id = %s",
                (tenant_id, document_id),
            )
            return _Claimed(storage_key=row[0], mime=row[1], lineage_id=row[2], version=row[3])
    except psycopg.OperationalError as error:
        raise TransientError("database") from error


def _index(
    connect: ConnectionFactory,
    embedder: Embedder,
    tenant_id: UUID,
    document_id: UUID,
    claimed: _Claimed,
    chunks: list[Chunk],
    vectors: list[list[float]],
) -> bool:
    try:
        with connect() as connection, connection.transaction(), connection.cursor() as cursor:
            _set_tenant(cursor, tenant_id)
            cursor.execute(
                "SELECT status FROM documents WHERE tenant_id = %s AND id = %s FOR UPDATE",
                (tenant_id, document_id),
            )
            row = cursor.fetchone()
            # The document was deleted, archived or re-queued while this job was running.
            # Its chunks must not appear, so write nothing.
            if row is None or row[0] != "PROCESSING":
                return False

            cursor.execute(
                "DELETE FROM document_chunks WHERE tenant_id = %s AND document_id = %s",
                (tenant_id, document_id),
            )
            cursor.executemany(
                """
                INSERT INTO document_chunks (id, tenant_id, document_id, chunk_index, content, page_number,
                                             heading, embedding, embedding_model, embedding_revision,
                                             embedding_dimension, active)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s::vector, %s, %s, %s, true)
                """,
                [
                    (
                        uuid4(),
                        tenant_id,
                        document_id,
                        chunk.index,
                        chunk.text,
                        chunk.page,
                        chunk.heading,
                        "[" + ",".join(repr(value) for value in vector) + "]",
                        embedder.model,
                        embedder.revision,
                        embedder.dimension,
                    )
                    for chunk, vector in zip(chunks, vectors, strict=True)
                ],
            )
            # Retire older versions of the same document in the same transaction, so there is never
            # a moment with two versions answering, and never a moment with none.
            cursor.execute(
                """
                UPDATE document_chunks SET active = false
                WHERE tenant_id = %s AND active AND document_id IN (
                    SELECT id FROM documents WHERE tenant_id = %s AND lineage_id = %s AND version < %s)
                """,
                (tenant_id, tenant_id, claimed.lineage_id, claimed.version),
            )
            cursor.execute(
                """
                UPDATE documents SET status = 'ARCHIVED', updated_at = now()
                WHERE tenant_id = %s AND lineage_id = %s AND version < %s AND status = 'READY'
                """,
                (tenant_id, claimed.lineage_id, claimed.version),
            )
            cursor.execute(
                """
                UPDATE documents
                SET status = 'READY', chunk_count = %s, error_code = NULL,
                    processed_at = now(), updated_at = now()
                WHERE tenant_id = %s AND id = %s
                """,
                (len(chunks), tenant_id, document_id),
            )
            return True
    except psycopg.OperationalError as error:
        raise TransientError("database") from error


def _mark_failed(connect: ConnectionFactory, tenant_id: UUID, document_id: UUID, code: str) -> None:
    with connect() as connection, connection.transaction(), connection.cursor() as cursor:
        _set_tenant(cursor, tenant_id)
        cursor.execute(
            """
            UPDATE documents SET status = 'FAILED', error_code = %s, updated_at = now()
            WHERE tenant_id = %s AND id = %s AND status = 'PROCESSING'
            """,
            (code, tenant_id, document_id),
        )
