"""The evaluation metrics, checked by hand on a tiny example."""

from pathlib import Path
from uuid import uuid4

from servora_ai.evaluation import Example, Label, load_dataset, matches, run, score
from servora_ai.retrieval import RetrievedChunk

EVALUATION = Path(__file__).resolve().parents[1] / "evaluation"


def chunk(filename: str, content: str, score_value: float) -> RetrievedChunk:
    return RetrievedChunk(uuid4(), uuid4(), filename, None, None, content, score_value)


def test_a_chunk_is_relevant_only_from_the_right_document_with_the_phrase():
    label = Label("refunds.md", "within 30 days")
    assert matches(chunk("refunds.md", "Returns accepted WITHIN  30\ndays.", 0.9), label)
    assert not matches(chunk("other.md", "Returns accepted within 30 days.", 0.9), label)
    assert not matches(chunk("refunds.md", "Returns accepted within 14 days.", 0.9), label)


def test_metrics_on_a_hand_checked_example():
    refund = Example("refund?", (Label("refunds.md", "30 days"),))
    shipping = Example("shipping?", (Label("shipping.md", "5 days"),))
    unknown = Example("helmets?", ())
    results = [
        # Relevant chunk first of two: precision 1/2, recall 1, hit@1 1, RR 1.
        (refund, [chunk("refunds.md", "30 days", 0.8), chunk("shipping.md", "5 days", 0.3)]),
        # Relevant chunk second: precision 1/2, recall 1, hit@1 0, RR 1/2. Its score is below 0.5.
        (shipping, [chunk("refunds.md", "30 days", 0.6), chunk("shipping.md", "5 days", 0.4)]),
        (unknown, [chunk("refunds.md", "30 days", 0.45)]),
    ]

    report = score(results, k=2, thresholds=[0.3, 0.5])

    assert report["answerable_questions"] == 2 and report["unanswerable_questions"] == 1
    assert report["precision_at_k"] == 0.5
    assert report["recall_at_k"] == 1.0
    assert report["hit_rate_at_1"] == 0.5
    assert report["mrr"] == 0.75
    low, high = report["gate"]
    assert low == {
        "threshold": 0.3,
        "answerable_with_relevant_evidence": 1.0,
        "unanswerable_correctly_abstained": 0.0,
    }
    assert high["answerable_with_relevant_evidence"] == 0.5
    assert high["unanswerable_correctly_abstained"] == 1.0


def test_k_cuts_off_the_ranking():
    example = Example("q", (Label("a.md", "x"),))
    results = [(example, [chunk("b.md", "y", 0.9), chunk("a.md", "x", 0.8)])]

    assert score(results, k=1, thresholds=[])["recall_at_k"] == 0.0
    assert score(results, k=2, thresholds=[])["recall_at_k"] == 1.0


def test_datasets_load_and_every_label_points_at_a_real_passage():
    for tenant in ("techstore", "hotelpro"):
        examples = load_dataset(EVALUATION / f"{tenant}.jsonl")
        assert len(examples) == 20
        assert sum(1 for example in examples if not example.relevant) == 5
        for example in examples:
            for label in example.relevant:
                text = (EVALUATION / "fixtures" / tenant / label.document).read_text(encoding="utf-8")
                assert label.phrase in text, (tenant, label)


def test_harness_runs_against_the_database_for_one_tenant(database):
    tenant_id = database.create_tenant("Eval")
    other = database.create_tenant("EvalOther")
    database.add_document(tenant_id, "refunds.md", ["Refunds are accepted within 30 days of purchase."])
    database.add_document(other, "refunds.md", ["Refunds are accepted within 30 days of purchase."])
    examples = [Example("Are refunds accepted within 30 days?", (Label("refunds.md", "within 30 days"),))]

    report = run(database.ai, database.embedder, tenant_id, examples, k=5, thresholds=[0.3])

    assert report["recall_at_k"] == 1.0
    # Only this tenant's single chunk was retrieved, so precision is 1 and not 1/2.
    assert report["precision_at_k"] == 1.0
    assert report["top_scores"][0]["top_is_relevant"] is True
