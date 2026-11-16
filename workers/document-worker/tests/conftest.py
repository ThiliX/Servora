"""Shared fixtures.

Database tests run against a real PostgreSQL with the real schema: the Flyway migrations from the
core API are applied as-is, and the pipeline connects as the unprivileged ``servora_worker`` role,
so Row-Level Security and column privileges are exercised exactly as in production.
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
os.environ.setdefault("SERVORA_POSTGRES_WORKER_PASSWORD", "unused")
os.environ.setdefault("SERVORA_STORAGE_ACCESS_KEY", "unused")
os.environ.setdefault("SERVORA_STORAGE_SECRET_KEY", "unused")
os.environ.setdefault("SERVORA_INTERNAL_TOKEN_SECRET", TOKEN_SECRET)

from servora_worker.config import Settings  # noqa: E402

MIGRATIONS = (
    Path(__file__).resolve().parents[3]
    / "services"
    / "core-api"
    / "src"
    / "main"
    / "resources"
    / "db"
    / "migration"
)
IMAGE = "pgvector/pgvector:0.8.7-pg18-bookworm"


class FakeStore:
    def __init__(self) -> None:
        self.objects: dict[str, bytes] = {}

    def get(self, key: str) -> bytes:
        return self.objects[key]


class Database:
    """Connection helpers plus seeding done as the owner, standing in for the core API."""

    def __init__(self, host: str, port: int, owner_password: str, worker_password: str) -> None:
        self._base = f"host={host} port={port} dbname=servora connect_timeout=10"
        self._owner = f"{self._base} user=servora_owner password={owner_password}"
        self._worker = f"{self._base} user=servora_worker password={worker_password}"

    def owner(self) -> psycopg.Connection:
        return psycopg.connect(self._owner, autocommit=True)

    def worker(self) -> psycopg.Connection:
        return psycopg.connect(self._worker)

    def create_tenant(self, name: str) -> UUID:
        tenant_id, source_id = uuid4(), uuid4()
        with self.owner() as connection:
            connection.execute(
                "INSERT INTO tenants (id, name, slug, status) VALUES (%s, %s, %s, 'ACTIVE')",
                (tenant_id, name, f"{name.lower()}-{uuid4().hex[:8]}"),
            )
            connection.execute(
                "INSERT INTO knowledge_sources (id, tenant_id, name, source_type, status) "
                "VALUES (%s, %s, 'Uploaded documents', 'UPLOAD', 'ACTIVE')",
                (source_id, tenant_id),
            )
        return tenant_id

    def create_document(
        self,
        store: FakeStore,
        tenant_id: UUID,
        content: bytes,
        mime: str = "text/plain",
        *,
        status: str = "QUEUED",
        lineage_id: UUID | None = None,
        version: int = 1,
    ) -> UUID:
        document_id = uuid4()
        key = f"tenants/{tenant_id}/documents/{document_id}"
        store.objects[key] = content
        with self.owner() as connection:
            connection.execute(
                """
                INSERT INTO documents (id, tenant_id, source_id, lineage_id, version, original_filename,
                                       storage_key, detected_mime, size_bytes, content_hash, status)
                SELECT %s, %s, s.id, %s, %s, 'file', %s, %s, %s, %s, %s
                FROM knowledge_sources s WHERE s.tenant_id = %s
                """,
                (
                    document_id,
                    tenant_id,
                    lineage_id or document_id,
                    version,
                    key,
                    mime,
                    len(content),
                    uuid4().hex,
                    status,
                    tenant_id,
                ),
            )
        return document_id

    def document(self, document_id: UUID) -> tuple[str, str | None, int]:
        with self.owner() as connection:
            return connection.execute(
                "SELECT status, error_code, chunk_count FROM documents WHERE id = %s", (document_id,)
            ).fetchone()

    def set_status(self, document_id: UUID, status: str) -> None:
        with self.owner() as connection:
            connection.execute("UPDATE documents SET status = %s WHERE id = %s", (status, document_id))

    def chunks(self, document_id: UUID, *, active_only: bool = False) -> list[tuple]:
        query = (
            "SELECT chunk_index, content, page_number, heading, embedding_model, embedding_dimension, "
            "active, tenant_id FROM document_chunks WHERE document_id = %s"
        )
        if active_only:
            query += " AND active"
        with self.owner() as connection:
            return connection.execute(query + " ORDER BY chunk_index", (document_id,)).fetchall()


@pytest.fixture(scope="session")
def database() -> Database:
    from testcontainers.postgres import PostgresContainer

    owner_password, worker_password = secrets.token_hex(12), secrets.token_hex(12)
    container = PostgresContainer(
        IMAGE, username="servora_owner", password=owner_password, dbname="servora", driver=None
    )
    with container:
        db = Database(
            container.get_container_host_ip(),
            int(container.get_exposed_port(5432)),
            owner_password,
            worker_password,
        )
        with db.owner() as connection:
            for role, password in (
                ("servora_app", secrets.token_hex(12)),
                ("servora_worker", worker_password),
            ):
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
                sql = sql.replace("${app_role}", "servora_app").replace("${worker_role}", "servora_worker")
                connection.execute(sql)
        yield db


@pytest.fixture
def store() -> FakeStore:
    return FakeStore()


@pytest.fixture
def settings() -> Settings:
    return Settings(chunk_max_chars=200, chunk_overlap_chars=30, max_pdf_pages=5, max_text_chars=20_000)
