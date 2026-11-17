"""Tenant-filtered retrieval and the evidence gate.

Every query binds the tenant for Row-Level Security and also filters by tenant explicitly. The
service connects with a read-only role, so even a bug here cannot change or leak across tenants.
Search is exact (no approximate index) until a benchmark justifies one (ADR-003).
"""

from collections.abc import Callable
from dataclasses import dataclass
from uuid import UUID

import psycopg

from .embeddings import Embedder

ConnectionFactory = Callable[[], psycopg.Connection]

_SEARCH = """
SELECT c.id, c.document_id, d.original_filename, c.page_number, c.heading, c.content,
       1 - (c.embedding <=> %(query)s::vector) AS score
FROM document_chunks c
JOIN documents d ON d.tenant_id = c.tenant_id AND d.id = c.document_id
WHERE c.tenant_id = %(tenant)s
  AND c.active
  AND d.status = 'READY'
  AND c.embedding_model = %(model)s
  AND c.embedding_revision = %(revision)s
  AND c.embedding_dimension = %(dimension)s
ORDER BY c.embedding <=> %(query)s::vector, c.id
LIMIT %(limit)s
"""


@dataclass(frozen=True)
class RetrievedChunk:
    chunk_id: UUID
    document_id: UUID
    filename: str
    page_number: int | None
    heading: str | None
    content: str
    score: float


def retrieve(
    connect: ConnectionFactory, embedder: Embedder, tenant_id: UUID, question: str, top_k: int
) -> list[RetrievedChunk]:
    """The tenant's nearest active chunks, best first.

    Only chunks indexed with the embedder's exact profile are compared: vectors from another
    model, revision or dimension are not comparable and are never searched.
    """
    vector = embedder.embed([question])[0]
    params = {
        "query": "[" + ",".join(repr(value) for value in vector) + "]",
        "tenant": tenant_id,
        "model": embedder.model,
        "revision": embedder.revision,
        "dimension": embedder.dimension,
        "limit": top_k,
    }
    with connect() as connection, connection.transaction(), connection.cursor() as cursor:
        cursor.execute("SELECT set_config('app.tenant_id', %s, true)", (str(tenant_id),))
        cursor.execute(_SEARCH, params)
        rows = cursor.fetchall()
    return [
        RetrievedChunk(
            chunk_id=row[0],
            document_id=row[1],
            filename=row[2],
            page_number=row[3],
            heading=row[4],
            content=row[5],
            score=float(row[6]),
        )
        for row in rows
    ]


def select_evidence(chunks: list[RetrievedChunk], min_score: float, max_chars: int) -> list[RetrievedChunk]:
    """The chunks good enough to answer from.

    A nearest neighbour always exists, so being nearest proves nothing: a chunk counts as evidence
    only if its similarity reaches the threshold. The result is capped by a character budget, best
    first, and may be empty, in which case the assistant must not answer.
    """
    evidence: list[RetrievedChunk] = []
    used = 0
    for chunk in chunks:
        if chunk.score < min_score:
            continue
        if evidence and used + len(chunk.content) > max_chars:
            break
        evidence.append(chunk)
        used += len(chunk.content)
    return evidence
