"""The answer pipeline: retrieve, gate, generate, verify.

Every path ends in one of a fixed set of outcomes. Only ANSWERED and ESCALATED carry text written
by the model; every other outcome returns the assistant's configured fallback message and asks for
a person, so a provider failure or a bad output is never shown to a customer.
"""

import logging
import time
from dataclasses import dataclass
from typing import Literal
from uuid import UUID

from opentelemetry import trace
from pydantic import BaseModel

from . import metrics
from .config import Settings
from .embeddings import Embedder
from .prompt import PROMPT_VERSION, AssistantProfile, build_prompt
from .providers import (
    CircuitBreaker,
    LlmProvider,
    ProviderMalformedOutput,
    ProviderRefused,
    ProviderUnavailable,
)
from .retrieval import ConnectionFactory, RetrievedChunk, retrieve, select_evidence

log = logging.getLogger(__name__)
tracer = trace.get_tracer("servora_ai.pipeline")

Outcome = Literal[
    "ANSWERED",
    "ESCALATED",
    "NO_EVIDENCE",
    "PROVIDER_UNAVAILABLE",
    "INVALID_OUTPUT",
    "INVALID_CITATION",
    "UNGROUNDED",
    "REFUSED",
]

SNIPPET_CHARS = 240


class Citation(BaseModel):
    chunk_id: UUID
    document_id: UUID
    filename: str
    page_number: int | None
    heading: str | None
    snippet: str


class RetrievalHit(BaseModel):
    chunk_id: UUID
    document_id: UUID
    filename: str
    score: float
    # Whether the chunk passed the evidence gate and was given to the model.
    used: bool


class AnswerResult(BaseModel):
    outcome: Outcome
    answer: str
    intent: str | None
    needs_escalation: bool
    escalation_reason: str | None
    citations: list[Citation]
    retrieval: list[RetrievalHit]
    provider: str
    model: str | None
    prompt_version: str
    embedding_model: str
    evidence_threshold: float
    latency_ms: int
    input_tokens: int
    output_tokens: int


@dataclass
class Dependencies:
    settings: Settings
    connect: ConnectionFactory
    embedder: Embedder
    provider: LlmProvider
    breaker: CircuitBreaker


def threshold_for(settings: Settings, confidence_policy: str) -> float:
    """STRICT assistants need stronger evidence before answering; anything else is BALANCED."""
    if confidence_policy == "STRICT":
        return max(settings.evidence_min_score, settings.evidence_strict_min_score)
    return settings.evidence_min_score


def answer_question(
    deps: Dependencies, tenant_id: UUID, assistant: AssistantProfile, question: str
) -> AnswerResult:
    started = time.monotonic()
    # The server span the API instrumentation opened for this request; the outcome is recorded on it.
    request_span = trace.get_current_span()
    settings = deps.settings
    threshold = threshold_for(settings, assistant.confidence_policy)
    with tracer.start_as_current_span("retrieve") as span:
        retrieval_started = time.monotonic()
        chunks = retrieve(deps.connect, deps.embedder, tenant_id, question, settings.retrieval_top_k)
        evidence = select_evidence(chunks, threshold, settings.max_context_chars)
        metrics.RETRIEVAL_SECONDS.observe(time.monotonic() - retrieval_started)
        top_score = max((chunk.score for chunk in chunks), default=0.0)
        # Counts and scores only: never the question or chunk text.
        span.set_attribute("servora.retrieval.top_k", settings.retrieval_top_k)
        span.set_attribute("servora.retrieval.retrieved", len(chunks))
        span.set_attribute("servora.retrieval.evidence", len(evidence))
        span.set_attribute("servora.retrieval.top_score", round(top_score, 4))
        span.set_attribute("servora.retrieval.threshold", threshold)
    metrics.RETRIEVED_CHUNKS.labels("retrieved").observe(len(chunks))
    metrics.RETRIEVED_CHUNKS.labels("used").observe(len(evidence))
    metrics.TOP_SCORE.observe(top_score)
    used = {chunk.chunk_id for chunk in evidence}

    def finish(
        outcome: Outcome,
        *,
        text: str | None = None,
        intent: str | None = None,
        escalate: bool = True,
        reason: str | None = None,
        cited: list[RetrievedChunk] | None = None,
        model: str | None = None,
        tokens: tuple[int, int] = (0, 0),
    ) -> AnswerResult:
        elapsed = time.monotonic() - started
        latency_ms = int(elapsed * 1000)
        metrics.ANSWERS.labels(outcome).inc()
        metrics.ANSWER_SECONDS.labels(outcome).observe(elapsed)
        metrics.CIRCUIT_OPEN.set(0 if deps.breaker.allows() else 1)
        if tokens[0]:
            metrics.TOKENS.labels(deps.provider.name, "input").inc(tokens[0])
        if tokens[1]:
            metrics.TOKENS.labels(deps.provider.name, "output").inc(tokens[1])
        request_span.set_attribute("servora.ai.outcome", outcome)
        request_span.set_attribute("servora.ai.prompt_version", PROMPT_VERSION)
        # Identifiers, counts and timings only: never the question, the prompt or document text.
        log.info(
            "answer tenant=%s outcome=%s retrieved=%d evidence=%d cited=%d latency_ms=%d",
            tenant_id,
            outcome,
            len(chunks),
            len(evidence),
            len(cited or []),
            latency_ms,
        )
        return AnswerResult(
            outcome=outcome,
            answer=text if text is not None else assistant.fallback_message,
            intent=intent,
            needs_escalation=escalate,
            escalation_reason=reason,
            citations=[
                Citation(
                    chunk_id=chunk.chunk_id,
                    document_id=chunk.document_id,
                    filename=chunk.filename,
                    page_number=chunk.page_number,
                    heading=chunk.heading,
                    snippet=" ".join(chunk.content.split())[:SNIPPET_CHARS],
                )
                for chunk in cited or []
            ],
            retrieval=[
                RetrievalHit(
                    chunk_id=chunk.chunk_id,
                    document_id=chunk.document_id,
                    filename=chunk.filename,
                    score=round(chunk.score, 4),
                    used=chunk.chunk_id in used,
                )
                for chunk in chunks
            ],
            provider=deps.provider.name,
            model=model,
            prompt_version=PROMPT_VERSION,
            embedding_model=deps.embedder.model,
            evidence_threshold=threshold,
            latency_ms=latency_ms,
            input_tokens=tokens[0],
            output_tokens=tokens[1],
        )

    if not evidence:
        # Nothing good enough to answer from: do not call the model at all.
        return finish("NO_EVIDENCE", reason="no_evidence")
    if not deps.breaker.allows():
        return finish("PROVIDER_UNAVAILABLE", reason="provider_unavailable")

    prompt = build_prompt(assistant, question, evidence)
    provider_started = time.monotonic()
    # The span describes the call (provider, model, tokens, result), never the prompt or the answer.
    with tracer.start_as_current_span("llm generate", kind=trace.SpanKind.CLIENT) as span:
        span.set_attribute("gen_ai.system", deps.provider.name)
        span.set_attribute("servora.ai.sources", len(prompt.sources))

        def provider_done(result_label: str) -> None:
            metrics.PROVIDER_SECONDS.labels(deps.provider.name, result_label).observe(
                time.monotonic() - provider_started
            )
            span.set_attribute("servora.ai.provider_result", result_label)

        try:
            result = deps.provider.generate(prompt)
        except ProviderUnavailable as error:
            deps.breaker.record_failure()
            provider_done("unavailable")
            span.set_status(trace.Status(trace.StatusCode.ERROR, "provider unavailable"))
            log.warning("provider unavailable reason=%s", error.reason)
            return finish("PROVIDER_UNAVAILABLE", reason="provider_unavailable")
        except ProviderMalformedOutput:
            deps.breaker.record_success()
            provider_done("malformed")
            return finish("INVALID_OUTPUT", reason="invalid_output")
        except ProviderRefused:
            deps.breaker.record_success()
            provider_done("refused")
            return finish("REFUSED", reason="provider_refused")
        deps.breaker.record_success()
        provider_done("ok")
        span.set_attribute("gen_ai.response.model", result.model or "")
        span.set_attribute("gen_ai.usage.input_tokens", result.input_tokens)
        span.set_attribute("gen_ai.usage.output_tokens", result.output_tokens)

    output = result.answer
    tokens = (result.input_tokens, result.output_tokens)
    if not output.answer.strip():
        return finish("INVALID_OUTPUT", reason="invalid_output", model=result.model, tokens=tokens)

    # Every cited id must be one of the sources this request retrieved for this tenant.
    labels = list(dict.fromkeys(output.citations))
    if any(label not in prompt.sources for label in labels):
        return finish("INVALID_CITATION", reason="invalid_citation", model=result.model, tokens=tokens)
    cited = [prompt.sources[label] for label in labels]

    if output.needs_escalation:
        return finish(
            "ESCALATED",
            text=output.answer.strip(),
            intent=output.intent,
            reason=output.escalation_reason or "model_requested",
            cited=cited,
            model=result.model,
            tokens=tokens,
        )
    if not cited:
        # A direct answer that cites nothing cannot be checked against the knowledge base.
        return finish("UNGROUNDED", reason="ungrounded", model=result.model, tokens=tokens)
    return finish(
        "ANSWERED",
        text=output.answer.strip(),
        intent=output.intent,
        escalate=False,
        cited=cited,
        model=result.model,
        tokens=tokens,
    )
