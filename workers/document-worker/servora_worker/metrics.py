"""Prometheus metrics for document processing (ADR-022).

The Celery worker runs jobs in child processes, so its metrics use the client's multi-process mode:
each process writes to files under ``PROMETHEUS_MULTIPROC_DIR`` and the worker's main process
serves the combined values (``serve_worker_metrics``). Labels are fixed sets (result, code, stage);
no tenant or document identifier is ever a label.
"""

import logging
import os

from prometheus_client import CollectorRegistry, Counter, Histogram, start_http_server
from prometheus_client.core import GaugeMetricFamily

log = logging.getLogger(__name__)

# Must exist before the first metric is written in multi-process mode.
_multiproc_dir = os.environ.get("PROMETHEUS_MULTIPROC_DIR")
if _multiproc_dir:
    os.makedirs(_multiproc_dir, exist_ok=True)

DOCUMENTS = Counter(
    "servora_worker_documents",
    "Documents processed, by result (ready, failed, superseded, skipped, retry) and failure code",
    ["result", "code"],
)

DOCUMENT_SECONDS = Histogram(
    "servora_worker_document_seconds",
    "Time to process one document, by result",
    ["result"],
    buckets=(0.5, 1, 2, 5, 10, 20, 30, 60, 120, 240, 300),
)

STAGE_SECONDS = Histogram(
    "servora_worker_stage_seconds",
    "Time spent in each processing stage",
    ["stage"],
    buckets=(0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 30, 60, 120),
)

QUEUE_WAIT_SECONDS = Histogram(
    "servora_worker_queue_wait_seconds",
    "Time a job waited in the queue before a worker started it",
    buckets=(0.1, 0.5, 1, 2, 5, 10, 30, 60, 300, 900, 3600),
)

ENQUEUED = Counter(
    "servora_worker_jobs_enqueued", "Jobs accepted by the enqueue endpoint, by result", ["result"]
)


class QueueDepthCollector:
    """Reads the Celery queue's length from Redis at scrape time."""

    def __init__(self, length) -> None:
        self._length = length

    def collect(self):
        gauge = GaugeMetricFamily("servora_worker_queue_depth", "Jobs waiting in the ingestion queue")
        try:
            gauge.add_metric([], float(self._length()))
        except Exception as error:  # Redis unavailable: report no value rather than failing the scrape.
            log.warning("queue depth unavailable: %s", type(error).__name__)
            return
        yield gauge


def serve_worker_metrics(port: int) -> None:
    """Serves the combined metrics of all worker processes. Called once, in the main process."""
    if not _multiproc_dir:
        log.warning("PROMETHEUS_MULTIPROC_DIR is not set; worker metrics are not served")
        return
    from prometheus_client import multiprocess

    # Values left by a previous run of this container would otherwise be added to this run's. The
    # job processes start after this, so every file present now is stale.
    for name in os.listdir(_multiproc_dir):
        if name.endswith(".db"):
            os.remove(os.path.join(_multiproc_dir, name))
    registry = CollectorRegistry()
    multiprocess.MultiProcessCollector(registry)
    start_http_server(port, registry=registry)
