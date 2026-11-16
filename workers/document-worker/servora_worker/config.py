"""Configuration, read from SERVORA_* environment variables and validated at startup."""

from functools import lru_cache

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="SERVORA_", extra="ignore")

    postgres_host: str = "localhost"
    postgres_port: int = 5432
    postgres_db: str = "servora"
    # The worker's own unprivileged role: knowledge tables only, subject to Row-Level Security.
    postgres_worker_user: str = "servora_worker"
    postgres_worker_password: SecretStr

    redis_url: str = "redis://localhost:6379/0"

    storage_endpoint: str = "http://localhost:7070"
    storage_region: str = "us-east-1"
    storage_bucket: str = "servora-documents"
    storage_access_key: SecretStr
    storage_secret_key: SecretStr

    # Base64 HMAC key shared with the core API. Signs the short-lived enqueue tokens.
    internal_token_secret: SecretStr

    # Embedding profile. Changing any of these means re-indexing every document (ADR-012).
    embedding_model: str = "sentence-transformers/all-MiniLM-L6-v2"
    embedding_revision: str = "1110a243fdf4706b3f48f1d95db1a4f5529b4d41"
    embedding_dimension: int = 384
    embedding_batch_size: int = Field(default=32, ge=1)

    # Chunking. The model reads at most 256 word pieces, roughly 1,000 characters of English, so
    # chunks stay under that. Evaluated against the labelled set in M3.
    chunk_max_chars: int = Field(default=800, ge=100)
    chunk_overlap_chars: int = Field(default=100, ge=0)

    # Limits that bound the work one document can cause.
    max_pdf_pages: int = Field(default=300, ge=1)
    max_text_chars: int = Field(default=2_000_000, ge=1)
    max_chunks: int = Field(default=5_000, ge=1)
    task_soft_time_limit_seconds: int = 240
    task_time_limit_seconds: int = 300
    task_max_retries: int = 3

    @property
    def postgres_conninfo(self) -> str:
        return (
            f"host={self.postgres_host} port={self.postgres_port} dbname={self.postgres_db} "
            f"user={self.postgres_worker_user} password={self.postgres_worker_password.get_secret_value()} "
            "connect_timeout=10"
        )


@lru_cache
def get_settings() -> Settings:
    return Settings()  # type: ignore[call-arg]
