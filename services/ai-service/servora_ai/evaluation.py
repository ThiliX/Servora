"""Retrieval evaluation harness.

Measures retrieval and the evidence gate against a labelled question set, using the real embedding
model and the real database through the service's own read-only role. It makes no LLM call, so it
costs nothing to run. Answer quality is a separate, later measurement.

A label names a document and a phrase; a retrieved chunk is relevant when it comes from that
document and contains the phrase. Chunk ids are not used because they change on every re-index.

    python -m servora_ai.evaluation --tenant <workspace id> --dataset evaluation/techstore.jsonl
"""

import argparse
import json
import sys
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from uuid import UUID

import psycopg

from .config import get_settings
from .embeddings import Embedder, SentenceTransformerEmbedder
from .retrieval import ConnectionFactory, RetrievedChunk, retrieve


@dataclass(frozen=True)
class Label:
    document: str
    phrase: str


@dataclass(frozen=True)
class Example:
    question: str
    relevant: tuple[Label, ...]


def load_dataset(path: Path) -> list[Example]:
    examples = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        examples.append(
            Example(
                row["question"], tuple(Label(item["document"], item["phrase"]) for item in row["relevant"])
            )
        )
    return examples


def _normalise(text: str) -> str:
    return " ".join(text.lower().split())


def matches(chunk: RetrievedChunk, label: Label) -> bool:
    return chunk.filename == label.document and _normalise(label.phrase) in _normalise(chunk.content)


def score(results: list[tuple[Example, list[RetrievedChunk]]], k: int, thresholds: list[float]) -> dict:
    """Aggregates metrics over (example, retrieved chunks) pairs. Pure, so it is unit tested."""
    answerable = [(example, chunks[:k]) for example, chunks in results if example.relevant]
    unanswerable = [(example, chunks[:k]) for example, chunks in results if not example.relevant]

    precision, recall, hit_at_1, reciprocal_rank = [], [], [], []
    for example, chunks in answerable:
        relevant_flags = [any(matches(chunk, label) for label in example.relevant) for chunk in chunks]
        found = sum(any(matches(chunk, label) for chunk in chunks) for label in example.relevant)
        precision.append(sum(relevant_flags) / len(chunks) if chunks else 0.0)
        recall.append(found / len(example.relevant))
        hit_at_1.append(1.0 if relevant_flags[:1] == [True] else 0.0)
        first = next((index for index, flag in enumerate(relevant_flags) if flag), None)
        reciprocal_rank.append(0.0 if first is None else 1.0 / (first + 1))

    def mean(values: list[float]) -> float | None:
        return round(sum(values) / len(values), 4) if values else None

    gate = []
    for threshold in thresholds:
        # Answerable: a relevant chunk must pass the gate. Unanswerable: nothing may pass.
        answered = [
            any(
                chunk.score >= threshold and any(matches(chunk, label) for label in example.relevant)
                for chunk in chunks
            )
            for example, chunks in answerable
        ]
        abstained = [all(chunk.score < threshold for chunk in chunks) for _, chunks in unanswerable]
        gate.append(
            {
                "threshold": threshold,
                "answerable_with_relevant_evidence": mean([1.0 if value else 0.0 for value in answered]),
                "unanswerable_correctly_abstained": mean([1.0 if value else 0.0 for value in abstained]),
            }
        )
    return {
        "k": k,
        "answerable_questions": len(answerable),
        "unanswerable_questions": len(unanswerable),
        "precision_at_k": mean(precision),
        "recall_at_k": mean(recall),
        "hit_rate_at_1": mean(hit_at_1),
        "mrr": mean(reciprocal_rank),
        "gate": gate,
    }


def run(
    connect: ConnectionFactory,
    embedder: Embedder,
    tenant_id: UUID,
    examples: list[Example],
    k: int,
    thresholds: list[float],
) -> dict:
    results = [(example, retrieve(connect, embedder, tenant_id, example.question, k)) for example in examples]
    report = score(results, k, thresholds)
    report["top_scores"] = [
        {
            "question": example.question,
            "answerable": bool(example.relevant),
            "top_score": round(chunks[0].score, 4) if chunks else None,
            "top_is_relevant": bool(chunks) and any(matches(chunks[0], label) for label in example.relevant),
        }
        for example, chunks in results
    ]
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate retrieval against a labelled question set.")
    parser.add_argument(
        "--tenant", required=True, type=UUID, help="workspace id holding the indexed documents"
    )
    parser.add_argument("--dataset", required=True, type=Path)
    parser.add_argument("--k", type=int, default=None, help="defaults to SERVORA_RETRIEVAL_TOP_K")
    parser.add_argument("--thresholds", default="0.25,0.30,0.35,0.40,0.45,0.50")
    args = parser.parse_args()

    settings = get_settings()
    embedder = SentenceTransformerEmbedder(
        settings.embedding_model, settings.embedding_revision, settings.embedding_dimension, batch_size=1
    )
    examples = load_dataset(args.dataset)
    report = run(
        lambda: psycopg.connect(settings.postgres_conninfo),
        embedder,
        args.tenant,
        examples,
        args.k or settings.retrieval_top_k,
        [float(value) for value in args.thresholds.split(",")],
    )
    report.update(
        dataset=args.dataset.name,
        sample_size=len(examples),
        embedding_model=settings.embedding_model,
        embedding_revision=settings.embedding_revision,
        date=date.today().isoformat(),
    )
    json.dump(report, sys.stdout, indent=2)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
