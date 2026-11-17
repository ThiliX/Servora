"""Internal authentication, request validation, and the Anthropic adapter's error mapping."""

import base64
import time
from types import SimpleNamespace
from uuid import uuid4

import anthropic
import httpx2
import jwt
import pytest
from conftest import TOKEN_SECRET, make_deps
from fastapi.testclient import TestClient
from google.genai import errors as genai_errors

from servora_ai.api import app, build_provider, get_dependencies
from servora_ai.auth import AUDIENCE, ISSUER, verify_token
from servora_ai.config import Settings
from servora_ai.prompt import Prompt
from servora_ai.providers import (
    AnthropicProvider,
    GeminiProvider,
    LlmAnswer,
    ProviderMalformedOutput,
    ProviderRefused,
    ProviderUnavailable,
)

KEY = base64.b64decode(TOKEN_SECRET)
BODY = {
    "question": "Are refunds accepted within 30 days?",
    "assistant": {
        "name": "TechStore Assistant",
        "tone": "FRIENDLY",
        "language": "en",
        "fallback_message": "Let me get a colleague.",
        "confidence_policy": "BALANCED",
    },
}


def token(tenant_id, *, key=KEY, audience=AUDIENCE, issuer=ISSUER, ttl=60, **extra):
    now = int(time.time())
    claims = {
        "iss": issuer,
        "aud": audience,
        "iat": now,
        "exp": now + ttl,
        "tenant_id": str(tenant_id),
        **extra,
    }
    return jwt.encode(claims, key, algorithm="HS256")


@pytest.fixture
def client(database, settings):
    app.dependency_overrides[get_dependencies] = lambda: make_deps(database, settings)
    yield TestClient(app)
    app.dependency_overrides.clear()


def test_health_needs_no_token(client):
    assert client.get("/health").json() == {"status": "UP"}


def test_answer_uses_the_tenant_from_the_token(client, database):
    tenant_id = database.create_tenant("Api")
    (chunk_id,) = database.add_document(
        tenant_id, "p.md", ["Refunds are accepted within 30 days of purchase."]
    )

    response = client.post(
        "/internal/v1/answers", json=BODY, headers={"Authorization": f"Bearer {token(tenant_id)}"}
    )

    assert response.status_code == 200
    body = response.json()
    assert body["outcome"] == "ANSWERED"
    assert body["citations"][0]["chunk_id"] == str(chunk_id)

    # The same question with another tenant's token finds nothing: the body cannot choose the tenant.
    other = client.post(
        "/internal/v1/answers",
        json={**BODY, "tenant_id": str(tenant_id)},
        headers={"Authorization": f"Bearer {token(database.create_tenant('Else'))}"},
    )
    assert other.json()["outcome"] == "NO_EVIDENCE"
    assert other.json()["retrieval"] == []


@pytest.mark.parametrize(
    "bad",
    [
        None,
        "Basic abc",
        "Bearer not-a-token",
        lambda t: f"Bearer {token(t, key=b'x' * 48)}",
        lambda t: f"Bearer {token(t, audience='servora-document-worker')}",
        lambda t: f"Bearer {token(t, issuer='someone-else')}",
        lambda t: f"Bearer {token(t, ttl=-60)}",
        lambda t: f"Bearer {jwt.encode({'tenant_id': str(t)}, KEY, algorithm='HS256')}",
    ],
)
def test_requests_without_a_valid_token_are_rejected(client, bad):
    header = bad(uuid4()) if callable(bad) else bad
    headers = {"Authorization": header} if header else {}

    assert client.post("/internal/v1/answers", json=BODY, headers=headers).status_code == 401


def test_token_without_a_tenant_is_invalid():
    now = int(time.time())
    no_tenant = jwt.encode(
        {"iss": ISSUER, "aud": AUDIENCE, "iat": now, "exp": now + 60}, KEY, algorithm="HS256"
    )
    with pytest.raises(ValueError):
        verify_token(no_tenant, TOKEN_SECRET)


def test_invalid_bodies_are_rejected(client):
    headers = {"Authorization": f"Bearer {token(uuid4())}"}
    assert (
        client.post("/internal/v1/answers", json={**BODY, "question": ""}, headers=headers).status_code == 422
    )
    too_long = {**BODY, "question": "x" * 2001}
    assert client.post("/internal/v1/answers", json=too_long, headers=headers).status_code == 422
    bad_tone = {**BODY, "assistant": {**BODY["assistant"], "tone": "RUDE"}}
    assert client.post("/internal/v1/answers", json=bad_tone, headers=headers).status_code == 422


def test_the_real_provider_requires_a_key():
    with pytest.raises(ValueError, match="SERVORA_ANTHROPIC_API_KEY"):
        Settings(llm_provider="anthropic")
    assert Settings().llm_provider == "fake"


# --- Anthropic adapter, with a stub client: no network, no key. ---

PROMPT = Prompt(system="system", user="user", sources={})
REQUEST = httpx2.Request("POST", "https://api.anthropic.com/v1/messages")


class StubClient:
    def __init__(self, outcome):
        self.kwargs = None
        self.beta = SimpleNamespace(messages=SimpleNamespace(parse=self._parse))
        self._outcome = outcome

    def _parse(self, **kwargs):
        self.kwargs = kwargs
        if isinstance(self._outcome, Exception):
            raise self._outcome
        return self._outcome


def status_error(cls, status):
    return cls("boom", response=httpx2.Response(status, request=REQUEST), body=None)


def message(parsed, stop_reason="end_turn"):
    return SimpleNamespace(
        stop_reason=stop_reason,
        parsed_output=parsed,
        model="claude-opus-5-5",
        usage=SimpleNamespace(input_tokens=120, output_tokens=40),
    )


ANSWER = LlmAnswer(
    answer="Yes.", intent="question", needs_escalation=False, escalation_reason=None, citations=["S1"]
)


def test_anthropic_request_shape_and_result():
    stub = StubClient(message(ANSWER))

    result = AnthropicProvider(stub, "claude-opus-5-5", 4000).generate(PROMPT)

    assert stub.kwargs["model"] == "claude-opus-5-5"
    assert stub.kwargs["system"] == "system"
    assert stub.kwargs["messages"] == [{"role": "user", "content": "user"}]
    assert stub.kwargs["output_format"] is LlmAnswer
    assert stub.kwargs["fallbacks"] == "default"
    assert "temperature" not in stub.kwargs and "thinking" not in stub.kwargs
    assert result.answer == ANSWER
    assert (result.model, result.input_tokens, result.output_tokens) == ("claude-opus-5-5", 120, 40)


@pytest.mark.parametrize(
    ("error", "reason"),
    [
        (anthropic.APITimeoutError(request=REQUEST), "timeout"),
        (anthropic.APIConnectionError(request=REQUEST), "connection"),
        (status_error(anthropic.RateLimitError, 429), "rate_limited"),
        (status_error(anthropic.AuthenticationError, 401), "authentication"),
        (status_error(anthropic.InternalServerError, 500), "server_error"),
        (status_error(anthropic.BadRequestError, 400), "request_rejected"),
    ],
)
def test_anthropic_failures_become_provider_unavailable(error, reason):
    with pytest.raises(ProviderUnavailable) as caught:
        AnthropicProvider(StubClient(error), "claude-opus-5-5", 4000).generate(PROMPT)
    assert caught.value.reason == reason


def test_anthropic_malformed_truncated_and_refused_outputs():
    provider = lambda outcome: AnthropicProvider(StubClient(outcome), "claude-opus-5-5", 4000)  # noqa: E731

    with pytest.raises(ProviderMalformedOutput):
        provider(ValueError("not json")).generate(PROMPT)
    with pytest.raises(ProviderMalformedOutput):
        provider(message(None)).generate(PROMPT)
    with pytest.raises(ProviderMalformedOutput):
        provider(message(ANSWER, stop_reason="max_tokens")).generate(PROMPT)
    with pytest.raises(ProviderRefused):
        provider(message(None, stop_reason="refusal")).generate(PROMPT)


# --- Gemini adapter, with a stub client: no network, no key. ---


class GeminiStub:
    def __init__(self, outcome):
        self.kwargs = None
        self.models = SimpleNamespace(generate_content=self._generate)
        self._outcome = outcome

    def _generate(self, **kwargs):
        self.kwargs = kwargs
        if isinstance(self._outcome, Exception):
            raise self._outcome
        return self._outcome


def gemini_response(parsed, finish="STOP", blocked=None):
    return SimpleNamespace(
        prompt_feedback=SimpleNamespace(block_reason=blocked) if blocked else None,
        candidates=[SimpleNamespace(finish_reason=SimpleNamespace(name=finish))],
        parsed=parsed,
        model_version="gemini-3.5-flash-lite",
        usage_metadata=SimpleNamespace(prompt_token_count=100, candidates_token_count=20),
    )


def test_gemini_request_shape_and_result():
    stub = GeminiStub(gemini_response(ANSWER))

    result = GeminiProvider(stub, "gemini-3.5-flash-lite", 4000).generate(PROMPT)

    assert stub.kwargs["model"] == "gemini-3.5-flash-lite"
    assert stub.kwargs["contents"] == "user"
    config = stub.kwargs["config"]
    assert config.system_instruction == "system"
    assert config.response_mime_type == "application/json"
    assert config.response_schema is LlmAnswer
    assert config.max_output_tokens == 4000
    assert result.answer == ANSWER
    assert (result.model, result.input_tokens, result.output_tokens) == ("gemini-3.5-flash-lite", 100, 20)


@pytest.mark.parametrize(
    ("error", "reason"),
    [
        (genai_errors.ClientError(429, {"error": {"message": "quota"}}), "rate_limited"),
        (genai_errors.ClientError(403, {"error": {"message": "bad key"}}), "authentication"),
        (genai_errors.ClientError(400, {"error": {"message": "bad request"}}), "request_rejected"),
        (genai_errors.ServerError(503, {"error": {"message": "overloaded"}}), "server_error"),
        (type("ReadTimeout", (Exception,), {})("slow"), "timeout"),
        (ConnectionError("refused"), "connection"),
    ],
)
def test_gemini_failures_become_provider_unavailable(error, reason):
    with pytest.raises(ProviderUnavailable) as caught:
        GeminiProvider(GeminiStub(error), "m", 4000).generate(PROMPT)
    assert caught.value.reason == reason


def test_gemini_blocked_truncated_and_unparsable_outputs():
    provider = lambda outcome: GeminiProvider(GeminiStub(outcome), "m", 4000)  # noqa: E731

    with pytest.raises(ProviderRefused):
        provider(gemini_response(None, blocked="SAFETY")).generate(PROMPT)
    with pytest.raises(ProviderRefused):
        provider(gemini_response(None, finish="SAFETY")).generate(PROMPT)
    with pytest.raises(ProviderMalformedOutput):
        provider(gemini_response(ANSWER, finish="MAX_TOKENS")).generate(PROMPT)
    with pytest.raises(ProviderMalformedOutput):
        provider(gemini_response(None)).generate(PROMPT)
    with pytest.raises(ProviderMalformedOutput):
        provider(ValueError("not json")).generate(PROMPT)


def test_gemini_needs_a_key_and_builds_a_client_without_network():
    with pytest.raises(ValueError, match="SERVORA_GEMINI_API_KEY"):
        Settings(llm_provider="gemini")
    settings = Settings(llm_provider="gemini", gemini_api_key="test-key-not-real")
    provider = build_provider(settings)
    assert isinstance(provider, GeminiProvider)
    assert provider.name == "gemini"
