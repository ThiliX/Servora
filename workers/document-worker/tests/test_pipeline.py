"""The pipeline against a real PostgreSQL, connected as the unprivileged worker role."""

from uuid import uuid4

import psycopg
import pytest
from pdfs import blank_pdf, text_pdf

from servora_worker.embeddings import HashingEmbedder
from servora_worker.errors import TransientError
from servora_worker.pipeline import mark_failed, process_document

EMBEDDER = HashingEmbedder(dimension=16)
LONG_TEXT = "\n\n".join(f"Policy paragraph {i}. " + "Details follow here. " * 6 for i in range(6)).encode()


def run(database, store, settings, tenant_id, document_id, embedder=EMBEDDER) -> str:
    return process_document(database.worker, store, embedder, settings, tenant_id, document_id)


def test_a_text_document_is_chunked_embedded_and_marked_ready(database, store, settings):
    tenant = database.create_tenant("TechStore")
    document = database.create_document(store, tenant, LONG_TEXT)

    assert run(database, store, settings, tenant, document) == "ready"

    status, error_code, chunk_count = database.document(document)
    chunks = database.chunks(document)
    assert (status, error_code) == ("READY", None)
    assert chunk_count == len(chunks) > 1
    assert [c[0] for c in chunks] == list(range(len(chunks)))
    assert all(len(c[1]) <= settings.chunk_max_chars for c in chunks)
    # Every chunk records its tenant and the embedding profile that produced it.
    assert {c[7] for c in chunks} == {tenant}
    assert {(c[4], c[5], c[6]) for c in chunks} == {(EMBEDDER.model, 16, True)}


def test_pdf_chunks_keep_their_page_numbers_and_markdown_chunks_their_headings(database, store, settings):
    tenant = database.create_tenant("HotelPro")
    pdf = database.create_document(
        store, tenant, text_pdf(["Check-in is at 2pm.", "Breakfast until 10am."]), "application/pdf"
    )
    markdown = database.create_document(
        store, tenant, b"# Amenities\nPool and gym.\n\n# Parking\nFree for guests.", "text/markdown"
    )

    assert run(database, store, settings, tenant, pdf) == "ready"
    assert run(database, store, settings, tenant, markdown) == "ready"

    assert [(c[2], c[1]) for c in database.chunks(pdf)] == [
        (1, "Check-in is at 2pm."),
        (2, "Breakfast until 10am."),
    ]
    assert [(c[3], c[1]) for c in database.chunks(markdown)] == [
        ("Amenities", "Pool and gym."),
        ("Parking", "Free for guests."),
    ]


def test_processing_twice_leaves_exactly_one_set_of_chunks(database, store, settings):
    tenant = database.create_tenant("Retry")
    document = database.create_document(store, tenant, LONG_TEXT)

    assert run(database, store, settings, tenant, document) == "ready"
    first = database.chunks(document)
    # A redelivered job finds the document already READY and does nothing.
    assert run(database, store, settings, tenant, document) == "skipped"
    # A job that crashed mid-way left the document in PROCESSING; the retry redoes it cleanly.
    database.set_status(document, "PROCESSING")
    assert run(database, store, settings, tenant, document) == "ready"

    assert database.chunks(document) == first
    assert database.document(document)[2] == len(first)


@pytest.mark.parametrize(
    ("content", "mime", "code"),
    [
        (blank_pdf(1, password="secret"), "application/pdf", "ENCRYPTED_PDF"),
        (blank_pdf(6), "application/pdf", "TOO_MANY_PAGES"),
        (blank_pdf(2), "application/pdf", "EMPTY_DOCUMENT"),
        (b"%PDF-1.7\nnot a real pdf at all", "application/pdf", "PARSE_ERROR"),
        (b"\xff\xfe\x00not utf8", "text/plain", "PARSE_ERROR"),
        (b"word " * 5_000, "text/plain", "TEXT_TOO_LARGE"),
    ],
)
def test_unprocessable_documents_fail_with_a_safe_code_and_no_chunks(
    database, store, settings, content, mime, code
):
    tenant = database.create_tenant("Bad")
    document = database.create_document(store, tenant, content, mime)

    assert run(database, store, settings, tenant, document) == "failed"

    assert database.document(document) == ("FAILED", code, 0)
    assert database.chunks(document) == []


@pytest.mark.parametrize("status", ["ARCHIVED", "DELETED", "READY", "FAILED"])
def test_documents_that_are_not_waiting_for_processing_are_left_alone(database, store, settings, status):
    tenant = database.create_tenant("Skip")
    document = database.create_document(store, tenant, LONG_TEXT, status=status)

    assert run(database, store, settings, tenant, document) == "skipped"

    assert database.document(document)[0] == status
    assert database.chunks(document) == []


def test_a_document_deleted_or_archived_during_processing_gets_no_chunks(database, store, settings):
    tenant = database.create_tenant("Race")

    class InterruptingEmbedder(HashingEmbedder):
        """Simulates the user acting while the slow embedding step is running."""

        def __init__(self, new_status: str, document_id) -> None:
            super().__init__(dimension=16)
            self._new_status, self._document_id = new_status, document_id

        def embed(self, texts):
            database.set_status(self._document_id, self._new_status)
            return super().embed(texts)

    for new_status in ("DELETED", "ARCHIVED"):
        document = database.create_document(store, tenant, LONG_TEXT + new_status.encode())
        outcome = run(database, store, settings, tenant, document, InterruptingEmbedder(new_status, document))

        assert outcome == "superseded"
        assert database.document(document)[0] == new_status
        assert database.chunks(document) == []


def test_a_new_version_retires_the_previous_one_in_the_same_step(database, store, settings):
    tenant = database.create_tenant("Versions")
    lineage = uuid4()
    v1 = database.create_document(
        store, tenant, b"Returns are accepted within 14 days.", lineage_id=lineage, version=1
    )
    assert run(database, store, settings, tenant, v1) == "ready"
    assert len(database.chunks(v1, active_only=True)) == 1

    v2 = database.create_document(
        store, tenant, b"Returns are accepted within 30 days.", lineage_id=lineage, version=2
    )
    # Until the new version is indexed, the old one keeps answering.
    assert len(database.chunks(v1, active_only=True)) == 1
    assert run(database, store, settings, tenant, v2) == "ready"

    assert database.document(v1)[0] == "ARCHIVED"
    assert database.chunks(v1, active_only=True) == []
    assert [c[1] for c in database.chunks(v2, active_only=True)] == ["Returns are accepted within 30 days."]


def test_a_failed_new_version_leaves_the_previous_version_answering(database, store, settings):
    tenant = database.create_tenant("KeepOld")
    lineage = uuid4()
    v1 = database.create_document(store, tenant, b"Opening hours are 9 to 5.", lineage_id=lineage, version=1)
    run(database, store, settings, tenant, v1)
    v2 = database.create_document(
        store, tenant, blank_pdf(1), "application/pdf", lineage_id=lineage, version=2
    )

    assert run(database, store, settings, tenant, v2) == "failed"

    assert database.document(v1)[0] == "READY"
    assert len(database.chunks(v1, active_only=True)) == 1


def test_a_job_naming_the_wrong_tenant_cannot_touch_the_document(database, store, settings):
    tenant_a = database.create_tenant("TenantA")
    tenant_b = database.create_tenant("TenantB")
    document_b = database.create_document(store, tenant_b, LONG_TEXT)

    # Tenant A's context with tenant B's document ID: Row-Level Security hides the row.
    assert run(database, store, settings, tenant_a, document_b) == "skipped"
    mark_failed(database.worker, tenant_a, document_b, "FORGED")

    assert database.document(document_b) == ("QUEUED", None, 0)
    assert database.chunks(document_b) == []


def test_each_tenants_chunks_are_invisible_to_the_other_through_the_worker_role(database, store, settings):
    tenant_a = database.create_tenant("IsoA")
    tenant_b = database.create_tenant("IsoB")
    document_a = database.create_document(store, tenant_a, b"TechStore refund policy: 30 days.")
    document_b = database.create_document(store, tenant_b, b"HotelPro check-in time: 2pm.")
    run(database, store, settings, tenant_a, document_a)
    run(database, store, settings, tenant_b, document_b)

    with database.worker() as connection:
        # No tenant context: nothing is visible.
        assert connection.execute("SELECT count(*) FROM document_chunks").fetchone()[0] == 0
        connection.rollback()
        connection.execute("SELECT set_config('app.tenant_id', %s, true)", (str(tenant_a),))
        contents = [row[0] for row in connection.execute("SELECT content FROM document_chunks WHERE active")]
        assert "TechStore refund policy: 30 days." in contents
        assert not any("HotelPro" in content for content in contents)
        # The worker role has no access outside the knowledge tables.
        connection.rollback()
        with pytest.raises(psycopg.errors.InsufficientPrivilege):
            connection.execute("SELECT count(*) FROM users")


def test_a_missing_stored_file_is_a_transient_failure_for_the_task_to_retry(database, settings):
    class UnavailableStore:
        def get(self, key):
            raise TransientError("storage")

    tenant = database.create_tenant("Outage")
    document = database.create_document(type("S", (), {"objects": {}})(), tenant, LONG_TEXT)

    with pytest.raises(TransientError):
        process_document(database.worker, UnavailableStore(), EMBEDDER, settings, tenant, document)

    # Left in PROCESSING so the retry can claim it again; after the last retry the task records a failure.
    assert database.document(document)[0] == "PROCESSING"
    mark_failed(database.worker, tenant, document, "PROCESSING_UNAVAILABLE")
    assert database.document(document) == ("FAILED", "PROCESSING_UNAVAILABLE", 0)
