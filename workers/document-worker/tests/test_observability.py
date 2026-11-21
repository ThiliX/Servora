"""ADR-022: the enqueue API continues the core API's trace and exposes metrics without tenant data."""

import base64
import time
from uuid import uuid4

import jwt
from conftest import TOKEN_SECRET
from fastapi.testclient import TestClient
from opentelemetry import trace
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from servora_worker import metrics
from servora_worker.api import AUDIENCE, ISSUER, app, get_enqueuer

TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"

_exporter = InMemorySpanExporter()
trace.get_tracer_provider().add_span_processor(SimpleSpanProcessor(_exporter))


def _token(tenant_id, document_id) -> str:
    now = int(time.time())
    claims = {
        "iss": ISSUER,
        "aud": AUDIENCE,
        "iat": now,
        "exp": now + 60,
        "tenant_id": str(tenant_id),
        "document_id": str(document_id),
    }
    return jwt.encode(claims, base64.b64decode(TOKEN_SECRET), algorithm="HS256")


def test_the_enqueue_request_continues_the_core_api_trace():
    seen: list[str] = []

    def enqueue(tenant_id, document_id):
        # The job is published inside the request's span, so its headers carry this trace.
        seen.append(format(trace.get_current_span().get_span_context().trace_id, "032x"))

    app.dependency_overrides[get_enqueuer] = lambda: enqueue
    _exporter.clear()
    try:
        response = TestClient(app).post(
            "/internal/v1/ingestion-jobs",
            headers={
                "Authorization": f"Bearer {_token(uuid4(), uuid4())}",
                "traceparent": f"00-{TRACE_ID}-00f067aa0ba902b7-01",
            },
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 202
    assert seen == [TRACE_ID]
    assert any(format(span.context.trace_id, "032x") == TRACE_ID for span in _exporter.get_finished_spans())


def test_metrics_count_enqueued_jobs_and_never_name_a_tenant():
    tenant_id = uuid4()
    app.dependency_overrides[get_enqueuer] = lambda: lambda tenant, document: None
    try:
        client = TestClient(app)
        client.post(
            "/internal/v1/ingestion-jobs",
            headers={"Authorization": f"Bearer {_token(tenant_id, uuid4())}"},
        )
        text = client.get("/metrics").text
    finally:
        app.dependency_overrides.clear()

    assert 'servora_worker_jobs_enqueued_total{result="accepted"}' in text
    assert str(tenant_id) not in text


def test_queue_depth_is_reported_and_a_broker_outage_does_not_break_the_scrape():
    assert [
        sample.value
        for family in metrics.QueueDepthCollector(lambda: 7).collect()
        for sample in family.samples
    ] == [7.0]

    def unavailable() -> int:
        raise ConnectionError("redis down")

    assert list(metrics.QueueDepthCollector(unavailable).collect()) == []
