"""LLM provider adapter (ADR-013).

One interface, two implementations: Anthropic Claude for real answers, and a deterministic fake
used by every automated test and as the default in development, so nothing is spent by accident.
Provider failures are mapped to three typed errors; the pipeline turns each into a safe fallback.
"""

import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Literal, Protocol

from pydantic import BaseModel, ValidationError

from .prompt import Prompt

FALLBACK_BETA = "server-side-fallback-2026-07-01"


class LlmAnswer(BaseModel):
    """The structured output every provider must return."""

    answer: str
    intent: Literal["question", "complaint", "human_request", "chitchat", "other"]
    needs_escalation: bool
    escalation_reason: str | None
    citations: list[str]


@dataclass(frozen=True)
class ProviderResult:
    answer: LlmAnswer
    model: str
    input_tokens: int
    output_tokens: int


class ProviderUnavailable(Exception):
    """Timeout, rate limit, outage or a rejected request. `reason` is a safe short code."""

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


class ProviderMalformedOutput(Exception):
    """The provider replied, but not with the required structure."""


class ProviderRefused(Exception):
    """The provider declined to answer."""


class LlmProvider(Protocol):
    name: str

    def generate(self, prompt: Prompt) -> ProviderResult: ...


class AnthropicProvider:
    """Claude through the official SDK, with structured output validated against LlmAnswer.

    The SDK retries connection errors, 408, 409, 429 and 5xx with backoff up to `max_retries`, so
    this class does not add its own retry loop. A policy decline is first re-run by the API on its
    default fallback model; only if that also declines does the caller see a refusal.
    """

    name = "anthropic"

    def __init__(self, client, model: str, max_tokens: int) -> None:
        self._client = client
        self._model = model
        self._max_tokens = max_tokens

    @classmethod
    def create(
        cls, api_key: str, model: str, max_tokens: int, timeout: float, max_retries: int
    ) -> "AnthropicProvider":
        import anthropic

        client = anthropic.Anthropic(api_key=api_key, timeout=timeout, max_retries=max_retries)
        return cls(client, model, max_tokens)

    def generate(self, prompt: Prompt) -> ProviderResult:
        import anthropic

        try:
            response = self._client.beta.messages.parse(
                model=self._model,
                max_tokens=self._max_tokens,
                system=prompt.system,
                messages=[{"role": "user", "content": prompt.user}],
                output_format=LlmAnswer,
                betas=[FALLBACK_BETA],
                fallbacks="default",
            )
        except anthropic.APITimeoutError as error:
            raise ProviderUnavailable("timeout") from error
        except anthropic.APIConnectionError as error:
            raise ProviderUnavailable("connection") from error
        except anthropic.RateLimitError as error:
            raise ProviderUnavailable("rate_limited") from error
        except anthropic.AuthenticationError as error:
            raise ProviderUnavailable("authentication") from error
        except anthropic.APIStatusError as error:
            reason = "server_error" if error.status_code >= 500 else "request_rejected"
            raise ProviderUnavailable(reason) from error
        except (ValidationError, ValueError) as error:
            raise ProviderMalformedOutput(str(type(error).__name__)) from error

        if response.stop_reason == "refusal":
            raise ProviderRefused()
        parsed = response.parsed_output
        if response.stop_reason == "max_tokens" or not isinstance(parsed, LlmAnswer):
            raise ProviderMalformedOutput("incomplete")
        return ProviderResult(
            answer=parsed,
            model=response.model,
            input_tokens=response.usage.input_tokens,
            output_tokens=response.usage.output_tokens,
        )


class FakeProvider:
    """Deterministic stand-in: quotes the best source and cites it. Makes no network calls.

    Tests pass `script` to play other behaviours (errors, bad citations, escalation).
    """

    name = "fake"
    model = "servora-fake-llm"

    def __init__(self, script: Callable[[Prompt], LlmAnswer] | None = None) -> None:
        self._script = script
        self.calls: list[Prompt] = []

    def generate(self, prompt: Prompt) -> ProviderResult:
        self.calls.append(prompt)
        answer = self._script(prompt) if self._script else self._default(prompt)
        return ProviderResult(
            answer=answer,
            model=self.model,
            input_tokens=(len(prompt.system) + len(prompt.user)) // 4,
            output_tokens=len(answer.answer) // 4,
        )

    @staticmethod
    def _default(prompt: Prompt) -> LlmAnswer:
        label, chunk = next(iter(prompt.sources.items()))
        text = " ".join(chunk.content.split())
        return LlmAnswer(
            answer=text[:300],
            intent="question",
            needs_escalation=False,
            escalation_reason=None,
            citations=[label],
        )


class CircuitBreaker:
    """Stops calling a provider that keeps failing, so customers get the fallback at once.

    State is in memory, per process. After `threshold` consecutive failures the breaker opens for
    `cooldown` seconds; the next call after that is allowed through as a trial.
    """

    def __init__(self, threshold: int, cooldown: float, clock: Callable[[], float] = time.monotonic) -> None:
        self._threshold = threshold
        self._cooldown = cooldown
        self._clock = clock
        self._failures = 0
        self._open_until = 0.0

    def allows(self) -> bool:
        return self._clock() >= self._open_until

    def record_success(self) -> None:
        self._failures = 0

    def record_failure(self) -> None:
        self._failures += 1
        if self._failures >= self._threshold:
            self._open_until = self._clock() + self._cooldown
            self._failures = 0
