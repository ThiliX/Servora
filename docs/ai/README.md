# AI documentation

Ingestion is built (M2). Retrieval, answering and evaluation are M3. No quality figure appears here because nothing has been measured yet.

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

## Still to write

| Topic | Milestone |
|---|---|
| Retrieval settings: top K, similarity threshold, evidence gate | M3 |
| Prompt versions | M3 |
| Evaluation dataset and labelling method | M3 |
| Evaluation results | M3, M7 |

## Evaluation rule

No quality figure is published without the benchmark dataset, sample size, labelling method, model version, thresholds and date. An LLM judge is never the only evaluator.
