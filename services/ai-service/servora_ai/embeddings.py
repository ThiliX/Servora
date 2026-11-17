"""Embedding providers.

Every stored vector records the model, revision and dimension that produced it. Vectors from
different profiles are not comparable, so a change of model means re-indexing (ADR-012).
"""

import hashlib
import math
import re
from typing import Protocol

_TOKEN = re.compile(r"\w+", re.UNICODE)


class Embedder(Protocol):
    model: str
    revision: str
    dimension: int

    def embed(self, texts: list[str]) -> list[list[float]]: ...


class SentenceTransformerEmbedder:
    """The production embedder. Loads the model once per worker process, on first use."""

    def __init__(self, model: str, revision: str, dimension: int, batch_size: int) -> None:
        self.model = model
        self.revision = revision
        self.dimension = dimension
        self._batch_size = batch_size
        self._encoder = None

    def embed(self, texts: list[str]) -> list[list[float]]:
        if self._encoder is None:
            # Imported lazily: PyTorch is large, and tests and the enqueue API do not need it.
            from sentence_transformers import SentenceTransformer

            self._encoder = SentenceTransformer(self.model, revision=self.revision, device="cpu")
        vectors = self._encoder.encode(
            texts, batch_size=self._batch_size, normalize_embeddings=True, show_progress_bar=False
        )
        result = [[float(value) for value in vector] for vector in vectors]
        if any(len(vector) != self.dimension for vector in result):
            raise RuntimeError("Embedding dimension does not match the configured embedding profile")
        return result


class HashingEmbedder:
    """Deterministic stand-in for tests: no model download, no network, stable output.

    It hashes tokens into buckets, so texts that share words get similar vectors. It is not a
    semantic model and must never be used to index real documents.
    """

    model = "servora-test/hashing"
    revision = "1"

    def __init__(self, dimension: int = 32) -> None:
        self.dimension = dimension

    def embed(self, texts: list[str]) -> list[list[float]]:
        return [self._embed_one(text) for text in texts]

    def _embed_one(self, text: str) -> list[float]:
        vector = [0.0] * self.dimension
        for token in _TOKEN.findall(text.lower()):
            digest = hashlib.sha256(token.encode("utf-8")).digest()
            bucket = int.from_bytes(digest[:4], "big") % self.dimension
            vector[bucket] += 1.0 if digest[4] % 2 == 0 else -1.0
        norm = math.sqrt(sum(value * value for value in vector))
        if norm == 0:
            vector[0] = 1.0
            return vector
        return [value / norm for value in vector]
