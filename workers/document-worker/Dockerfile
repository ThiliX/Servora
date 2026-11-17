# Servora document worker: the internal enqueue API and the Celery ingestion worker share this image.
FROM python:3.13.18-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1     PYTHONUNBUFFERED=1     PIP_NO_CACHE_DIR=1     PIP_DISABLE_PIP_VERSION_CHECK=1     HF_HOME=/opt/huggingface

WORKDIR /app

# The layers down to the baked model are kept identical in the document worker and AI service
# Dockerfiles, so Docker builds and stores them once for both images.

# CPU-only PyTorch. The default wheel bundles GPU libraries that this service never uses.
RUN pip install --index-url https://download.pytorch.org/whl/cpu "torch==2.15.0"
RUN pip install "sentence-transformers==6.3.0"

# Bake the pinned embedding model into the image so it is never downloaded at runtime.
# These must match SERVORA_EMBEDDING_MODEL and SERVORA_EMBEDDING_REVISION.
ARG EMBEDDING_MODEL=sentence-transformers/all-MiniLM-L6-v2
ARG EMBEDDING_REVISION=1110a243fdf4706b3f48f1d95db1a4f5529b4d41
RUN python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('${EMBEDDING_MODEL}', revision='${EMBEDDING_REVISION}', device='cpu')"

# No network access to the model hub at runtime.
ENV HF_HUB_OFFLINE=1     TRANSFORMERS_OFFLINE=1

COPY pyproject.toml ./
COPY servora_worker ./servora_worker
RUN pip install ".[model]"
# torch pulls in setuptools 78.1.0, which has known vulnerabilities (pip-audit, M7).
RUN pip install "setuptools==84.1.0"

RUN useradd --system --uid 10001 --home-dir /app worker && chown -R worker /opt/huggingface
USER worker

CMD ["celery", "--app", "servora_worker.tasks", "worker", "--loglevel", "INFO", "--concurrency", "1"]
