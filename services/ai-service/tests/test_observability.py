"""ADR-022: the trace continues from the core API, spans and metrics carry no content, JSON logs."""

import base64
import json
import logging
import sys
import time

import jwt
import pytest
from conftest import TOKEN_SECRET, make_deps
from fastapi.testclient import TestClient
from opentelemetry import trace
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from servora_ai.api import app, get_dependencies
from servora_ai.auth import AUDIENCE, ISSUER
from servora_ai.observability import JsonFormatter, _TraceContextFilter

QUESTION = "Are refunds accepted within 30 days?"
TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
BODY = {
    "question": QUESTION,
    "assistant": {
        "name": "TechStore Assistant",
        "tone": "FRIENDLY",
        "language": "en",
        "fallback_message": "Let me get a colleague.",
        "confidence_policy": "BALANCED",
    },
}

_exporter = InMemorySpanExporter()
trace.get_tracer_provider().add_span_processor(SimpleSpanProcessor(_exporter))


def _token(tenant_id) -> str:
    now = int(time.time())
    claims = {"iss": ISSUER, "aud": AUDIENCE, "iat": now, "exp": now + 60, "tenant_id": str(tenant_id)}
    return jwt.encode(claims, base64.b64decode(TOKEN_SECRET), algorithm="HS256")


@pytest.fixture
def client(database, settings):
    app.dependency_overrides[get_dependencies] = lambda: make_deps(database, settings)
    _exporter.clear()
    yield TestClient(app)
    app.dependency_overrides.clear()


def test_the_core_api_trace_is_continued_and_spans_hold_no_content(client, database):
    tenant_id = database.create_tenant("Traced")
    database.add_document(tenant_id, "refunds.md", ["Refunds are accepted within 30 days of purchase."])

    response = client.post(
        "/internal/v1/answers",
        json=BODY,
        headers={
            "Authorization": f"Bearer {_token(tenant_id)}",
            "traceparent": f"00-{TRACE_ID}-00f067aa0ba902b7-01",
        },
    )

    assert response.status_code == 200
    # The test's own database setup also produces spans; the request's are those in its trace.
    spans = [
        span for span in _exporter.get_finished_spans() if format(span.context.trace_id, "032x") == TRACE_ID
    ]
    assert {"retrieve", "llm generate"} <= {span.name for span in spans}
    retrieve = next(span for span in spans if span.name == "retrieve")
    assert retrieve.attributes["servora.retrieval.retrieved"] >= 1
    server = next(span for span in spans if span.attributes.get("servora.ai.outcome"))
    assert server.attributes["servora.ai.outcome"] == "ANSWERED"
    # Neither the question nor the document text appears in any span attribute.
    for span in spans:
        for value in span.attributes.values():
            assert "refund" not in str(value).lower()
    # Database statements are traced with placeholders, never their parameters.
    statements = [s.attributes.get("db.statement", "") for s in spans if s.attributes.get("db.statement")]
    assert statements
    assert all(str(tenant_id) not in statement for statement in statements)


def test_metrics_count_answers_by_outcome_without_tenant_labels(client, database):
    tenant_id = database.create_tenant("Metered")
    database.add_document(tenant_id, "refunds.md", ["Refunds are accepted within 30 days of purchase."])
    client.post("/internal/v1/answers", json=BODY, headers={"Authorization": f"Bearer {_token(tenant_id)}"})

    metrics = client.get("/metrics")

    assert metrics.status_code == 200
    text = metrics.text
    assert 'servora_ai_answers_total{outcome="ANSWERED"}' in text
    assert "servora_ai_retrieval_seconds_count" in text
    assert "servora_ai_top_score_bucket" in text
    assert str(tenant_id) not in text


def test_json_log_lines_carry_the_trace_and_span_ids():
    formatter = JsonFormatter("servora-ai-service")
    with trace.get_tracer("test").start_as_current_span("unit") as span:
        record = logging.LogRecord(
            "servora_ai.test", logging.INFO, __file__, 1, "answer outcome=%s", ("X",), None
        )
        _TraceContextFilter().filter(record)
        line = json.loads(formatter.format(record))

    assert line["message"] == "answer outcome=X"
    assert line["service.name"] == "servora-ai-service"
    assert line["trace_id"] == format(span.get_span_context().trace_id, "032x")
    assert line["span_id"] == format(span.get_span_context().span_id, "016x")


def test_json_log_lines_name_the_exception_type_but_not_its_message():
    formatter = JsonFormatter("servora-ai-service")
    try:
        raise ValueError("secret document text")
    except ValueError:
        record = logging.LogRecord("x", logging.ERROR, __file__, 1, "failed", (), sys.exc_info())
    record.trace_id = record.span_id = ""

    line = formatter.format(record)

    assert json.loads(line)["error.type"] == "ValueError"
    assert "secret document text" not in line
