"""Shared fixtures.

Tests run against a real PostgreSQL with the real schema: the core API's Flyway migrations are
applied as-is and the service connects as the unprivileged, read-only ``servora_ai`` role, so
Row-Level Security and table privileges are exercised exactly as in production. The embedder is
the deterministic hashing stand-in and the provider is the fake: no model download, no paid call.
"""

import base64
import os
import re
import secrets
from pathlib import Path
from uuid import UUID, uuid4

import psycopg
import pytest

TOKEN_SECRET = base64.b64encode(secrets.token_bytes(48)).decode()
os.environ.setdefault("SERVORA_POSTGRES_AI_PASSWORD", "unused")
os.environ.setdefault("SERVORA_INTERNAL_TOKEN_SECRET", TOKEN_SECRET)

from servora_ai.config import Settings  # noqa: E402
from servora_ai.embeddings import HashingEmbedder  # noqa: E402
from servora_ai.pipeline import Dependencies  # noqa: E402
from servora_ai.prompt import AssistantProfile  # noqa: E402
from servora_ai.providers import CircuitBreaker, FakeProvider  # noqa: E402

MIGRATIONS = (
    Path(__file__).resolve().parents[2] / "core-api" / "src" / "main" / "resources" / "db" / "migration"
)
IMAGE = "pgvector/pgvector:0.8.7-pg18-bookworm"
ROLES = {"${app_role}": "servora_app", "${worker_role}": "servora_worker", "${ai_role}": "servora_ai"}

ASSISTANT = AssistantProfile(
    name="TechStore Assistant",
    tone="FRIENDLY",
    language="en",
    fallback_message="Let me get a colleague to help.",
    confidence_policy="BALANCED",
)


class Database:
    """Connection helpers plus seeding done as the owner, standing in for the core API and worker."""

    def __init__(self, host: str, port: int, owner_password: str, ai_password: str) -> None:
        base = f"host={host} port={port} dbname=servora connect_timeout=10"
        self._owner = f"{base} user=servora_owner password={owner_password}"
        self._ai = f"{base} user=servora_ai password={ai_password}"
        self.embedder = HashingEmbedder(dimension=64)

    def owner(self) -> psycopg.Connection:
        return psycopg.connect(self._owner, autocommit=True)

    def ai(self) -> psycopg.Connection:
        return psycopg.connect(self._ai)

    def create_tenant(self, name: str) -> UUID:
        tenant_id = uuid4()
        with self.owner() as connection:
            connection.execute(
                "INSERT INTO tenants (id, name, slug, status) VALUES (%s, %s, %s, 'ACTIVE')",
                (tenant_id, name, f"{name.lower()}-{uuid4().hex[:8]}"),
            )
            connection.execute(
                "INSERT INTO knowledge_sources (id, tenant_id, name, source_type, status) "
                "VALUES (%s, %s, 'Uploaded documents', 'UPLOAD', 'ACTIVE')",
                (uuid4(), tenant_id),
            )
        return tenant_id

    def add_document(
        self,
        tenant_id: UUID,
        filename: str,
        chunks: list[str],
        *,
        status: str = "READY",
        active: bool = True,
        model: str | None = None,
    ) -> list[UUID]:
        """Inserts a document and its chunks, embedded with the test embedder. Returns chunk ids."""
        document_id = uuid4()
        chunk_ids = []
        with self.owner() as connection:
            connection.execute(
                """
                INSERT INTO documents (id, tenant_id, source_id, lineage_id, version, original_filename,
                                       storage_key, detected_mime, size_bytes, content_hash, status)
                SELECT %s, %s, s.id, %s, 1, %s, %s, 'text/plain', 1, %s, %s
                FROM knowledge_sources s WHERE s.tenant_id = %s
                """,
                (
                    document_id,
                    tenant_id,
                    document_id,
                    filename,
                    f"k/{document_id}",
                    uuid4().hex,
                    status,
                    tenant_id,
                ),
            )
            for index, (text, vector) in enumerate(zip(chunks, self.embedder.embed(chunks), strict=True)):
                chunk_id = uuid4()
                chunk_ids.append(chunk_id)
                connection.execute(
                    """
                    INSERT INTO document_chunks (id, tenant_id, document_id, chunk_index, content,
                        page_number, embedding, embedding_model, embedding_revision, embedding_dimension,
                        active)
                    VALUES (%s, %s, %s, %s, %s, 1, %s::vector, %s, %s, %s, %s)
                    """,
                    (
                        chunk_id,
                        tenant_id,
                        document_id,
                        index,
                        text,
                        "[" + ",".join(repr(v) for v in vector) + "]",
                        model or self.embedder.model,
                        self.embedder.revision,
                        self.embedder.dimension,
                        active,
                    ),
                )
        return chunk_ids


@pytest.fixture(scope="session")
def database() -> Database:
    from testcontainers.postgres import PostgresContainer

    owner_password, ai_password = secrets.token_hex(12), secrets.token_hex(12)
    container = PostgresContainer(
        IMAGE, username="servora_owner", password=owner_password, dbname="servora", driver=None
    )
    with container:
        db = Database(
            container.get_container_host_ip(),
            int(container.get_exposed_port(5432)),
            owner_password,
            ai_password,
        )
        with db.owner() as connection:
            for role in ROLES.values():
                password = ai_password if role == "servora_ai" else secrets.token_hex(12)
                connection.execute(
                    f"CREATE ROLE {role} LOGIN PASSWORD '{password}' "
                    "NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS"
                )
            migrations = sorted(
                MIGRATIONS.glob("V*.sql"), key=lambda p: int(re.match(r"V(\d+)", p.name).group(1))
            )
            assert migrations, f"no migrations found in {MIGRATIONS}"
            for migration in migrations:
                sql = migration.read_text(encoding="utf-8")
                for placeholder, role in ROLES.items():
                    sql = sql.replace(placeholder, role)
                connection.execute(sql)
        yield db


@pytest.fixture
def settings() -> Settings:
    return Settings(evidence_min_score=0.3, evidence_strict_min_score=0.6, breaker_failure_threshold=2)


def make_deps(database: Database, settings: Settings, provider=None, breaker=None) -> Dependencies:
    return Dependencies(
        settings=settings,
        connect=database.ai,
        embedder=database.embedder,
        provider=provider or FakeProvider(),
        breaker=breaker
        or CircuitBreaker(settings.breaker_failure_threshold, settings.breaker_cooldown_seconds),
    )
