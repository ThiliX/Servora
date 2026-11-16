# Servora document worker

Extracts, normalises, chunks, embeds and indexes uploaded knowledge documents. Python package `servora_worker`. Design: [docs/ai/README.md](../../docs/ai/README.md), [ADR-006](../../docs/architecture/adr/0006-asynchronous-worker-for-document-ingestion.md), [ADR-010](../../docs/architecture/adr/0010-outbox-plus-an-internal-enqueue-endpoint-for-java-to-celery.md).

One image, two processes:

| Process | Command | Purpose |
|---|---|---|
| `worker-api` | `uvicorn servora_worker.api:app` | Internal endpoint the core API calls to queue a document. Verifies a signed token and reads the tenant and document from it |
| `document-worker` | `celery --app servora_worker.tasks worker` | Runs the ingestion pipeline |

| Module | Contents |
|---|---|
| `api.py` | Enqueue endpoint and token verification |
| `tasks.py` | Celery app, retry policy, failure handling |
| `pipeline.py` | Claim, download, extract, chunk, embed, index |
| `extract.py`, `chunking.py` | Text extraction and deterministic chunking |
| `embeddings.py` | The model-backed embedder and the deterministic test embedder |
| `storage.py` | Read access to private document storage |
| `config.py` | Settings from `SERVORA_*` environment variables |

## Run

The worker runs in Docker Compose from the repository root:

```bash
docker compose up -d --build --wait
```

## Test

Tests need Docker. They start PostgreSQL through Testcontainers, apply the core API's migrations, and run the pipeline as the unprivileged `servora_worker` role. They use a fake embedder, so PyTorch is not installed.

```bash
pip install -e ".[dev]" && ruff check . && ruff format --check . && pytest -q
```

If `pip` cannot reach PyPI from your machine, run the same commands in a container from the repository root (Git Bash on Windows needs `MSYS_NO_PATHCONV=1` in front):

```bash
docker run --rm -v "$PWD:/repo" -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -e TESTCONTAINERS_RYUK_DISABLED=true -w /repo/workers/document-worker python:3.13.18-slim-bookworm sh -c "pip install -q -e '.[dev]' && ruff check . && ruff format --check . && pytest -q"
```

## Rules

- The tenant and document come only from the verified token. Never read them from a request body.
- Every transaction sets `app.tenant_id` first, and every statement also filters by tenant.
- Chunks are written only in the transaction that marks the document `READY`.
- Failures store a fixed error code. Never log or store document text, file names or exception messages.
