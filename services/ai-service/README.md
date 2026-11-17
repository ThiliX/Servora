# Servora AI service

Answers a customer question from one tenant's knowledge base: query embedding, tenant-filtered retrieval, an evidence gate, prompt assembly, the LLM provider adapter, structured output and citation verification. Python package `servora_ai`. Design: [docs/ai/README.md](../../docs/ai/README.md), [ADR-013](../../docs/architecture/adr/0013-llm-provider-behind-an-adapter.md).

It is an internal service. Only the core API calls it, with a short-lived signed token that carries the tenant.

| Module | Contents |
|---|---|
| `api.py` | `POST /internal/v1/answers`, `/health`, `/ready` |
| `auth.py` | Internal token verification |
| `retrieval.py` | Exact vector search under Row-Level Security, and the evidence gate |
| `prompt.py` | The versioned prompt; labels and escapes untrusted text |
| `providers.py` | Provider interface, Anthropic Claude, Google Gemini, the fake provider, circuit breaker |
| `pipeline.py` | Retrieve, gate, generate, verify; the fixed set of outcomes |
| `embeddings.py` | The model-backed embedder and the deterministic test embedder (same profile as the worker) |
| `evaluation.py` | Retrieval evaluation harness |
| `config.py` | Settings from `SERVORA_*` environment variables |

## Run

The service runs in Docker Compose from the repository root, on port 8091:

```bash
docker compose up -d --build --wait
```

By default it uses the fake provider, which quotes the best matching source and makes no network call. For real answers, choose a provider in `.env` and restart the service with `docker compose up -d ai-service`.

Google Gemini, which has a free tier (get a key at <https://aistudio.google.com/apikey>):

```text
SERVORA_LLM_PROVIDER=gemini
SERVORA_GEMINI_API_KEY=<your key>
```

Anthropic Claude (paid):

```text
SERVORA_LLM_PROVIDER=anthropic
SERVORA_ANTHROPIC_API_KEY=<your key>
```

On Gemini's free tier, Google may use the content sent to improve its products, so use it with demo data only.

## Test

Tests need Docker. They start PostgreSQL through Testcontainers, apply the core API's migrations, and run retrieval as the read-only `servora_ai` role. They use a fake embedder and the fake provider, so PyTorch is not installed and nothing is spent.

```bash
pip install -e ".[dev]" && ruff check . && ruff format --check . && pytest -q
```

If `pip` cannot reach PyPI from your machine, run the same commands in a container, as described in the [document worker README](../../workers/document-worker/README.md).

## Evaluate retrieval

The harness measures retrieval and the evidence gate with the real embedding model against documents indexed in a running stack. It makes no LLM call.

1. Create a workspace and upload the files in `evaluation/fixtures/techstore` (or `hotelpro`) on the Knowledge page. Wait until they are Ready.
2. Run the harness with the workspace id (shown in the dashboard URL):

```bash
docker compose run --rm ai-service python -m servora_ai.evaluation --tenant <workspace id> --dataset evaluation/techstore.jsonl
```

Each line of a dataset is a question and the passages that answer it, named by document and a phrase the passage contains. Questions with an empty `relevant` list are ones the knowledge base cannot answer; the assistant should abstain on them.
