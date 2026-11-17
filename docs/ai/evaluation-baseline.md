# Retrieval evaluation baseline

First measurement of retrieval and the evidence gate. It measures whether the right passage is found and whether the gate lets it through. It does not measure answer quality: no LLM was called.

## Setup

| Item | Value |
|---|---|
| Date | 2026-11-17 |
| Datasets | `services/ai-service/evaluation/techstore.jsonl`, `hotelpro.jsonl` |
| Sample size | 40 questions: 20 per tenant, of which 15 answerable and 5 unanswerable |
| Corpus | 3 Markdown documents per tenant (`evaluation/fixtures`), 15 and 17 chunks |
| Labelling method | Written by hand by the author of the fixtures. Each answerable question names one document and a phrase from the passage that answers it; a retrieved chunk is relevant if it comes from that document and contains the phrase. Questions were worded differently from the source text on purpose. One labeller, no second review |
| Embedding model | `sentence-transformers/all-MiniLM-L6-v2`, revision `1110a243fdf4706b3f48f1d95db1a4f5529b4d41`, 384 dimensions |
| Chunking | 800 characters maximum, 100 overlap, split at headings |
| Search | Exact cosine similarity, top K = 5, tenant-filtered under Row-Level Security |
| How it was run | Documents uploaded through the real API and indexed by the real worker; `python -m servora_ai.evaluation` in the AI service container, connected as `servora_ai` |

## Retrieval

| Metric | TechStore | HotelPro | Both |
|---|---|---|---|
| Recall@5 | 1.00 | 0.93 | 0.97 |
| Hit rate@1 | 0.93 | 0.73 | 0.83 |
| MRR | 0.97 | 0.83 | 0.90 |
| Precision@5 | 0.20 | 0.19 | 0.19 |

Precision@5 cannot exceed 0.20 here, because each question has exactly one relevant passage and five are retrieved. It is reported for completeness and says little on this dataset.

One answerable HotelPro question ("I booked the cheap prepaid rate. Can I get my money back?") did not retrieve its passage in the top five.

## Evidence gate

The gate lets a chunk through only if its similarity reaches a threshold. Two things are measured: how often an answerable question has its relevant passage pass the gate, and how often an unanswerable question has nothing pass (a correct abstention before any model call).

| Threshold | Answerable with relevant evidence (TechStore / HotelPro / both) | Unanswerable correctly abstained (TechStore / HotelPro / both) |
|---|---|---|
| 0.25 | 1.00 / 0.87 / 0.93 | 0.60 / 0.60 / 0.60 |
| 0.30 | 1.00 / 0.80 / 0.90 | 0.80 / 0.60 / 0.70 |
| **0.35** (BALANCED) | 1.00 / 0.80 / 0.90 | 1.00 / 0.60 / 0.80 |
| 0.40 | 0.93 / 0.67 / 0.80 | 1.00 / 0.80 / 0.90 |
| **0.45** (STRICT) | 0.87 / 0.53 / 0.70 | 1.00 / 1.00 / 1.00 |
| 0.50 | 0.60 / 0.40 / 0.50 | 1.00 / 1.00 / 1.00 |

## What this shows

- Retrieval finds the right passage in the top five for 29 of 30 answerable questions.
- **No single threshold separates answerable from unanswerable questions.** On HotelPro the scores overlap: the highest-scoring unanswerable question reached 0.43, while four answerable questions scored below 0.36. The gate alone is therefore not sufficient, which is why the prompt also tells the model to decline when the sources do not contain the answer, and why an answer without a citation is rejected.
- The defaults were kept at 0.35 (BALANCED) and 0.45 (STRICT). STRICT abstained on every unanswerable question but also turned away 30 percent of answerable ones, which become handoffs to a person. BALANCED answers more and relies more on the model declining.
- Short questions with little shared vocabulary ("Can I bring my dog?" scored 0.4497, just under STRICT) are the typical near misses.

## Limits of this baseline

- 40 questions over 6 short documents written for the purpose, by one person. Treat the figures as a first reading, not a quality claim. The differences between neighbouring thresholds rest on one or two questions each.
- English only, Markdown only, no long documents, no near-duplicate documents.
- Chunk size and overlap were not varied.
- Answer quality, citation accuracy of real model output, and behaviour under prompt injection with a real model have not been measured.

To repeat the measurement, see [services/ai-service/README.md](../../services/ai-service/README.md).
