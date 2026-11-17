# AI documentation

Ingestion (M2) and retrieval and answering (M3) are built. Retrieval has a first measured baseline: [evaluation-baseline.md](evaluation-baseline.md). Answer quality has not been measured.

## Ingestion pipeline

Implemented in `workers/document-worker`.

1. **Claim.** The task locks the document row and moves it to `PROCESSING`. Documents that are archived, deleted or already finished are skipped.
2. **Download** the file from private storage.
3. **Extract.**
   - PDF: text per page with pypdf, keeping the page number. Encrypted PDFs and PDFs over the page limit are refused. There is no OCR, so a scanned PDF without a text layer fails as empty.
   - Markdown: split at headings; each section keeps its heading. Headings inside fenced code blocks are ignored.
   - Plain text: one section.
4. **Normalise.** Unicode NFC, Unix line endings, control characters removed, runs of spaces collapsed, at most one blank line between paragraphs.
5. **Chunk** (below).
6. **Embed** all chunks in batches.
7. **Index.** In one transaction: replace the document's chunks, deactivate the chunks of older versions in the same lineage and archive those versions, and mark the document `READY`. If the document was deleted or archived meanwhile, nothing is written.

The job is idempotent: running it again, or after a crash, leaves exactly one set of chunks. Transient failures (storage, database) are retried with backoff up to three times; permanent failures record a safe error code.

## Limits

| Limit | Default | Setting |
|---|---|---|
| Upload size | 10 MB | `SERVORA_MAX_UPLOAD_BYTES` (core API) |
| PDF pages | 300 | `SERVORA_MAX_PDF_PAGES` |
| Extracted text | 2,000,000 characters | `SERVORA_MAX_TEXT_CHARS` |
| Chunks per document | 5,000 | `SERVORA_MAX_CHUNKS` |
| Task time | 240 s soft, 300 s hard | `SERVORA_TASK_SOFT_TIME_LIMIT_SECONDS`, `SERVORA_TASK_TIME_LIMIT_SECONDS` |
| Worker memory and CPU | 2 GB, 2 CPUs | `docker-compose.yml` |

## Chunking

Structural and deterministic: the same document always produces the same chunks.

- Text is split at paragraph boundaries first, then sentence boundaries, and mid-sentence only when a single sentence exceeds the limit.
- A chunk never spans two sections, so its page number or heading is exact.
- **Maximum 800 characters, 100 characters of overlap** (`SERVORA_CHUNK_MAX_CHARS`, `SERVORA_CHUNK_OVERLAP_CHARS`). The overlap repeats the last words of the previous chunk, cut at a word boundary, and is skipped when it would not fit.

These values are a starting point, not a result. The model reads at most 256 word pieces, roughly 1,000 characters of English, so 800 keeps a chunk inside what the model actually sees. They are to be evaluated against the labelled question set in M3.

## Embedding profile

| Property | Value |
|---|---|
| Model | `sentence-transformers/all-MiniLM-L6-v2` |
| Revision | `1110a243fdf4706b3f48f1d95db1a4f5529b4d41` |
| Dimension | 384 |
| Normalisation | L2-normalised, so cosine similarity equals the inner product |
| Runtime | CPU-only PyTorch, model baked into the worker image, no network access to the model hub at runtime |

Every chunk row stores the model, revision and dimension that produced it, and the database checks the stored dimension against the vector. Vectors from different profiles are not comparable: changing the model means a new profile and re-indexing every document ([ADR-012](../architecture/adr/0012-local-sentence-transformers-model-for-embeddings.md)). Query embeddings in M3 must use the same profile.

Tests use a deterministic hashing embedder instead of the model. It is not semantic and is never used on real documents.

## Answering a question

Implemented in `services/ai-service`. The core API calls it with the question and the assistant's configuration; the tenant comes from the signed token.

1. **Embed the question** with the same embedding profile as the chunks.
2. **Retrieve** the 5 nearest chunks by exact cosine similarity (`SERVORA_RETRIEVAL_TOP_K`). Only active chunks of `READY` documents of this tenant, indexed with exactly this model, revision and dimension, are searched.
3. **Evidence gate.** A nearest neighbour always exists, so being nearest proves nothing. A chunk counts as evidence only if its similarity reaches the threshold: 0.35 for a `BALANCED` assistant (`SERVORA_EVIDENCE_MIN_SCORE`), 0.45 for `STRICT` (`SERVORA_EVIDENCE_STRICT_MIN_SCORE`). Evidence is capped at 6,000 characters. If nothing passes, the model is not called and the outcome is `NO_EVIDENCE`.
4. **Prompt.** A versioned system prompt (`PROMPT_VERSION`, currently `2026-11-17.1`) plus the evidence and the question. Each passage is wrapped in a `<source id="S1">` tag and the question in a `<customer_message>` tag; angle brackets inside them are escaped so they cannot close a tag. The prompt tells the model to answer only from the sources, cite them, hand off on conflict, and treat tagged content as data.
5. **Generate** through the provider adapter ([ADR-013](../architecture/adr/0013-llm-provider-behind-an-adapter.md)) with a structured output schema: `answer`, `intent`, `needs_escalation`, `escalation_reason`, `citations`.
6. **Verify.** Every cited id must be one of the sources given in this request. A direct answer with no citation is rejected.
7. **Verify again in the core API**, against the database, in the caller's tenant. Then the interaction is stored in `ai_interactions`.

### Outcomes

| Outcome | When | Text shown |
|---|---|---|
| `ANSWERED` | Valid reply with at least one verified citation | The model's reply |
| `ESCALATED` | Valid reply in which the model asks for a person | The model's reply |
| `NO_EVIDENCE` | Nothing passed the gate | Fallback message |
| `PROVIDER_UNAVAILABLE` | Timeout, rate limit, outage, rejected request, or circuit open | Fallback message |
| `INVALID_OUTPUT` | Reply did not match the schema, was cut off, or was empty | Fallback message |
| `INVALID_CITATION` | Reply cited an id it was not given | Fallback message |
| `UNGROUNDED` | Direct answer with no citation | Fallback message |
| `REFUSED` | The provider declined | Fallback message |
| `SERVICE_UNAVAILABLE` | The core API could not reach the AI service | Fallback message |

Every outcome except `ANSWERED` sets `needs_escalation`. The fallback message is the one configured on the assistant.

### What is tested, and with what

All automated tests use a fake provider and a hashing embedder. They prove the pipeline's control flow: abstention, failure handling, citation checks, escaping, tenant filtering. They do not prove anything about how a real model words an answer or resists manipulation. The hashing embedder matches shared words, not meaning, so semantic matching is covered only by the evaluation baseline, which uses the real embedding model.

The Anthropic adapter is tested against a stubbed SDK client for request shape and error mapping. **No request has been sent to the live Anthropic API from this repository's tests or recorded runs.**

## Evaluation rule

No quality figure is published without the benchmark dataset, sample size, labelling method, model version, thresholds and date. An LLM judge is never the only evaluator.
