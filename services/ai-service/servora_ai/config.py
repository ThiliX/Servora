"""Configuration, read from SERVORA_* environment variables and validated at startup."""

from functools import lru_cache
from typing import Literal

from pydantic import Field, SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="SERVORA_", extra="ignore")

    postgres_host: str = "localhost"
    postgres_port: int = 5432
    postgres_db: str = "servora"
    # The AI service's own unprivileged role: read-only on documents and chunks, under RLS.
    postgres_ai_user: str = "servora_ai"
    postgres_ai_password: SecretStr

    # Base64 HMAC key shared with the core API. Signs the short-lived internal tokens.
    internal_token_secret: SecretStr

    # Embedding profile. Must equal the profile the worker indexed with (ADR-012).
    embedding_model: str = "sentence-transformers/all-MiniLM-L6-v2"
    embedding_revision: str = "1110a243fdf4706b3f48f1d95db1a4f5529b4d41"
    embedding_dimension: int = 384

    # Retrieval and the evidence gate. Values and their evaluation are in docs/ai/README.md.
    retrieval_top_k: int = Field(default=5, ge=1, le=20)
    evidence_min_score: float = Field(default=0.35, ge=0, le=1)
    evidence_strict_min_score: float = Field(default=0.45, ge=0, le=1)
    max_context_chars: int = Field(default=6_000, ge=500)
    max_question_chars: int = Field(default=2_000, ge=1)

    # "fake" is a deterministic stand-in that makes no network calls. It is the default so the
    # stack and the tests never spend money by accident.
    llm_provider: Literal["fake", "anthropic", "gemini"] = "fake"
    anthropic_api_key: SecretStr | None = None
    llm_model: str = "claude-opus-5-5"
    # Google Gemini. The default is the cheapest stable Flash-Lite model, which has a free tier.
    gemini_api_key: SecretStr | None = None
    gemini_model: str = "gemini-3.5-flash-lite"
    llm_max_tokens: int = Field(default=4_000, ge=256)
    llm_timeout_seconds: float = Field(default=30.0, gt=0)
    llm_max_retries: int = Field(default=2, ge=0, le=5)

    # After this many consecutive provider failures, skip the provider for the cooldown.
    breaker_failure_threshold: int = Field(default=3, ge=1)
    breaker_cooldown_seconds: float = Field(default=30.0, gt=0)

    @model_validator(mode="after")
    def _require_key_for_real_provider(self) -> "Settings":
        if self.llm_provider == "anthropic" and (
            self.anthropic_api_key is None or not self.anthropic_api_key.get_secret_value().strip()
        ):
            raise ValueError("SERVORA_ANTHROPIC_API_KEY is required when SERVORA_LLM_PROVIDER=anthropic")
        if self.llm_provider == "gemini" and (
            self.gemini_api_key is None or not self.gemini_api_key.get_secret_value().strip()
        ):
            raise ValueError("SERVORA_GEMINI_API_KEY is required when SERVORA_LLM_PROVIDER=gemini")
        return self

    @property
    def postgres_conninfo(self) -> str:
        return (
            f"host={self.postgres_host} port={self.postgres_port} dbname={self.postgres_db} "
            f"user={self.postgres_ai_user} password={self.postgres_ai_password.get_secret_value()} "
            "connect_timeout=10"
        )


@lru_cache
def get_settings() -> Settings:
    return Settings()  # type: ignore[call-arg]
