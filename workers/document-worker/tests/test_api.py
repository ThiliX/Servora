import base64
import secrets
import time
from uuid import uuid4

import jwt
from conftest import TOKEN_SECRET
from fastapi.testclient import TestClient

from servora_worker.api import AUDIENCE, ISSUER, app, get_enqueuer

URL = "/internal/v1/ingestion-jobs"


def _token(secret: str = TOKEN_SECRET, **overrides) -> str:
    now = int(time.time())
    claims = {
        "iss": ISSUER,
        "aud": AUDIENCE,
        "iat": now,
        "exp": now + 60,
        "tenant_id": str(uuid4()),
        "document_id": str(uuid4()),
    } | overrides
    claims = {key: value for key, value in claims.items() if value is not None}
    return jwt.encode(claims, base64.b64decode(secret), algorithm="HS256")


def _client(enqueued: list, *, fail: bool = False) -> TestClient:
    def enqueue(tenant_id, document_id):
        if fail:
            raise ConnectionError("broker down")
        enqueued.append((tenant_id, document_id))

    app.dependency_overrides[get_enqueuer] = lambda: enqueue
    return TestClient(app)


def test_a_valid_token_enqueues_the_tenant_and_document_it_names():
    enqueued: list = []
    tenant_id, document_id = uuid4(), uuid4()
    token = _token(tenant_id=str(tenant_id), document_id=str(document_id))

    response = _client(enqueued).post(URL, headers={"Authorization": f"Bearer {token}"})

    assert response.status_code == 202
    assert enqueued == [(tenant_id, document_id)]


def test_the_request_body_cannot_change_which_document_is_processed():
    enqueued: list = []
    tenant_id, document_id = uuid4(), uuid4()
    token = _token(tenant_id=str(tenant_id), document_id=str(document_id))

    response = _client(enqueued).post(
        URL,
        headers={"Authorization": f"Bearer {token}"},
        json={"tenant_id": str(uuid4()), "document_id": str(uuid4())},
    )

    assert response.status_code == 202
    assert enqueued == [(tenant_id, document_id)]


def test_requests_without_a_valid_token_are_rejected():
    enqueued: list = []
    client = _client(enqueued)
    other_key = base64.b64encode(secrets.token_bytes(48)).decode()
    now = int(time.time())
    bad_tokens = {
        "wrong key": _token(secret=other_key),
        "expired": _token(iat=now - 600, exp=now - 300),
        "wrong audience": _token(aud="servora-ai-service"),
        "wrong issuer": _token(iss="someone-else"),
        "no expiry": _token(exp=None),
        "no tenant": _token(tenant_id=None),
        "tenant not a uuid": _token(tenant_id="../../etc"),
        "unsigned": jwt.encode({"iss": ISSUER, "aud": AUDIENCE}, key="", algorithm="none"),
        "garbage": "not-a-token",
    }

    assert client.post(URL).status_code == 401
    assert client.post(URL, headers={"Authorization": "Basic abc"}).status_code == 401
    for label, token in bad_tokens.items():
        response = client.post(URL, headers={"Authorization": f"Bearer {token}"})
        assert response.status_code == 401, label
        assert response.json() == {"detail": "unauthenticated"}, label
    assert enqueued == []


def test_a_broker_failure_is_reported_so_the_outbox_retries():
    response = _client([], fail=True).post(URL, headers={"Authorization": f"Bearer {_token()}"})

    assert response.status_code == 503
    assert "broker" not in response.text


def test_health_is_open_and_interactive_docs_are_disabled():
    client = _client([])

    assert client.get("/health").json() == {"status": "UP"}
    assert client.get("/docs").status_code == 404
    assert client.get("/openapi.json").status_code == 404
