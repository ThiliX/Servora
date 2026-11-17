"""Internal HTTP API. Reachable only by the core API, on the internal network."""

from functools import lru_cache
from typing import Annotated, Literal

import psycopg
from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

from .auth import verify_token
from .config import Settings, get_settings
from .embeddings import SentenceTransformerEmbedder
from .pipeline import AnswerResult, Dependencies, answer_question
from .prompt import AssistantProfile
from .providers import AnthropicProvider, CircuitBreaker, FakeProvider, GeminiProvider, LlmProvider


class AssistantInput(BaseModel):
    name: str = Field(min_length=1, max_length=80)
    tone: Literal["FRIENDLY", "PROFESSIONAL", "CONCISE"]
    language: str = Field(min_length=2, max_length=5)
    fallback_message: str = Field(min_length=1, max_length=500)
    confidence_policy: Literal["STRICT", "BALANCED"]


class AnswerRequest(BaseModel):
    question: str = Field(min_length=1)
    assistant: AssistantInput


def build_provider(settings: Settings) -> LlmProvider:
    if settings.llm_provider == "anthropic":
        return AnthropicProvider.create(
            api_key=settings.anthropic_api_key.get_secret_value(),  # validated in Settings
            model=settings.llm_model,
            max_tokens=settings.llm_max_tokens,
            timeout=settings.llm_timeout_seconds,
            max_retries=settings.llm_max_retries,
        )
    if settings.llm_provider == "gemini":
        return GeminiProvider.create(
            api_key=settings.gemini_api_key.get_secret_value(),  # validated in Settings
            model=settings.gemini_model,
            max_tokens=settings.llm_max_tokens,
            timeout=settings.llm_timeout_seconds,
            max_retries=settings.llm_max_retries,
        )
    return FakeProvider()


@lru_cache
def get_dependencies() -> Dependencies:
    settings = get_settings()
    return Dependencies(
        settings=settings,
        connect=lambda: psycopg.connect(settings.postgres_conninfo),
        embedder=SentenceTransformerEmbedder(
            settings.embedding_model, settings.embedding_revision, settings.embedding_dimension, batch_size=1
        ),
        provider=build_provider(settings),
        breaker=CircuitBreaker(settings.breaker_failure_threshold, settings.breaker_cooldown_seconds),
    )


# No interactive docs: this is an internal service.
app = FastAPI(title="Servora AI service", docs_url=None, redoc_url=None, openapi_url=None)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP"}


@app.get("/ready")
def ready(deps: Annotated[Dependencies, Depends(get_dependencies)]) -> dict[str, str]:
    """Checks the database and loads the embedding model, so the first real request is not slow."""
    try:
        with deps.connect() as connection:
            connection.execute("SELECT 1")
        deps.embedder.embed(["ready"])
    except Exception:
        raise HTTPException(status_code=503, detail="not ready") from None
    return {"status": "READY", "provider": deps.provider.name}


@app.post("/internal/v1/answers")
def create_answer(
    request: AnswerRequest,
    deps: Annotated[Dependencies, Depends(get_dependencies)],
    authorization: Annotated[str | None, Header()] = None,
) -> AnswerResult:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="unauthenticated")
    try:
        tenant_id = verify_token(
            authorization.removeprefix("Bearer "), deps.settings.internal_token_secret.get_secret_value()
        )
    except ValueError:
        raise HTTPException(status_code=401, detail="unauthenticated") from None
    if len(request.question) > deps.settings.max_question_chars:
        raise HTTPException(status_code=422, detail="question too long")
    assistant = AssistantProfile(**request.assistant.model_dump())
    try:
        return answer_question(deps, tenant_id, assistant, request.question.strip())
    except psycopg.Error:
        raise HTTPException(status_code=503, detail="knowledge unavailable") from None
