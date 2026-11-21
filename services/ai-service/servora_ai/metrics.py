"""Prometheus metrics for the answer pipeline (ADR-022).

Labels are fixed sets only (outcome, provider, result). No tenant, question or document ever
becomes a label.
"""

from prometheus_client import Counter, Gauge, Histogram

ANSWERS = Counter("servora_ai_answers", "Answers produced, by outcome", ["outcome"])

ANSWER_SECONDS = Histogram(
    "servora_ai_answer_seconds",
    "Time to produce an answer, retrieval to verification",
    ["outcome"],
    buckets=(0.1, 0.25, 0.5, 1, 2, 5, 10, 20, 30, 60, 90),
)

RETRIEVAL_SECONDS = Histogram(
    "servora_ai_retrieval_seconds",
    "Embedding the question and searching the tenant's chunks",
    buckets=(0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2, 5),
)

RETRIEVED_CHUNKS = Histogram(
    "servora_ai_retrieved_chunks",
    "Chunks returned by the search, and how many passed the evidence gate",
    ["stage"],
    buckets=(0, 1, 2, 3, 5, 8, 13, 20),
)

TOP_SCORE = Histogram(
    "servora_ai_top_score",
    "Similarity of the best chunk for each question (0 when nothing was found)",
    buckets=(0.1, 0.2, 0.3, 0.35, 0.4, 0.45, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0),
)

PROVIDER_SECONDS = Histogram(
    "servora_ai_provider_seconds",
    "Time spent waiting for the model provider",
    ["provider", "result"],
    buckets=(0.25, 0.5, 1, 2, 5, 10, 20, 30, 60),
)

TOKENS = Counter(
    "servora_ai_tokens", "Model tokens used, as reported by the provider", ["provider", "direction"]
)

CIRCUIT_OPEN = Gauge("servora_ai_provider_circuit_open", "1 while the provider circuit breaker is open")
