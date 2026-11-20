"""OpenAI provider (also used for OpenAI-compatible endpoints such as Groq)."""

from __future__ import annotations

import re
import time

from ..schemas import GenerationRequest, GenerationResponse
from .base import LLMProviderBase

#: How many times a rate-limited or transiently failing call is retried.
#: Free / shared inference endpoints (Groq, OpenAI free tier) throttle
#: aggressively when several documents are generated back to back, so a
#: single 429 must not fail the whole generation job.
MAX_RETRIES = 4
#: Base delay for the exponential backoff, in seconds.
BASE_BACKOFF = 2.0
#: Upper bound for a single backoff wait, in seconds.
MAX_BACKOFF = 30.0

#: Markers that identify a *quota exhaustion* rather than a momentary
#: rate limit. A momentary rate limit clears in seconds; a daily token
#: quota does not clear until the provider's quota window resets.
#:
#: Retrying the latter is pure waste: the call cannot succeed before
#: the window resets, and every retry burns wall-clock time that the
#: caller is waiting for. The markers are matched against the error
#: text, which is where the OpenAI-compatible providers (Groq, Together,
#: Fireworks) put the quota explanation.
_QUOTA_MARKERS = (
    "tokens per day",
    "tokens per minute",
    "requests per day",
    "requests per minute",
    "daily limit",
    "monthly limit",
    "quota exceeded",
    "insufficient_quota",
    "billing_hard_limit_reached",
)

#: How long a provider asks us to wait before trying again, e.g.
#: "Please try again in 17m14.208s." Only a wait that fits inside
#: {@link MAX_BACKOFF} is worth honouring — a longer one means the
#: quota window has to reset first, and retrying cannot help.
_RETRY_AFTER_PATTERN = re.compile(r"try again in\s+([\d.]+)\s*([smh]?)", re.IGNORECASE)

#: Unit multipliers for the retry-after hint, in seconds.
_UNIT_SECONDS = {"": 1.0, "s": 1.0, "m": 60.0, "h": 3600.0}


class OpenAIProvider(LLMProviderBase):
    name = "openai"

    def __init__(self, *, api_key: str, base_url: str | None = None, timeout: float = 60.0) -> None:
        if not api_key:
            raise ValueError("OpenAIProvider requires an api_key")
        try:
            from openai import OpenAI  # type: ignore
        except ImportError as e:  # pragma: no cover
            raise RuntimeError("openai SDK not installed") from e
        self.client = OpenAI(api_key=api_key, base_url=base_url, timeout=timeout)

    def complete(self, request: GenerationRequest) -> GenerationResponse:
        messages = [{"role": m.role, "content": m.content} for m in request.messages]
        last_error: Exception | None = None

        for attempt in range(MAX_RETRIES + 1):
            try:
                resp = self.client.chat.completions.create(
                    model=request.model,
                    messages=messages,
                    temperature=request.temperature,
                    max_tokens=request.max_tokens,
                    stop=request.stop or None,
                )
            except Exception as exc:  # noqa: BLE001 - re-raised below
                if not _is_retryable(exc) or attempt == MAX_RETRIES:
                    raise
                delay = _retry_delay(exc, attempt)
                if delay is None:
                    # The provider told us when it will accept traffic
                    # again, and that moment is further away than we are
                    # willing to wait. Retrying now would fail again.
                    raise
                last_error = exc
                time.sleep(delay)
                continue

            text = resp.choices[0].message.content or ""
            usage = getattr(resp, "usage", None)
            return GenerationResponse(
                text=text,
                model=request.model,
                input_tokens=getattr(usage, "prompt_tokens", 0) if usage else 0,
                output_tokens=getattr(usage, "completion_tokens", 0) if usage else 0,
                provider=self.name,
                raw=resp.model_dump() if hasattr(resp, "model_dump") else None,
            )

        # Unreachable: the loop either returns or raises.
        raise last_error or RuntimeError("OpenAI provider failed without an error")


def _is_retryable(exc: Exception) -> bool:
    """Whether the failure is worth retrying.

    Rate limits (429) and server-side errors (5xx) are transient; a bad
    key (401/403) or a malformed request (400) will fail again no matter
    how often we retry, so they are surfaced immediately.
    """

    status = _status_of(exc)
    if status is None:
        return False
    return status == 429 or 500 <= int(status) < 600


def _status_of(exc: Exception) -> int | None:
    """The HTTP status the provider answered with, if any."""

    status = getattr(exc, "status_code", None)
    if status is None:
        response = getattr(exc, "response", None)
        status = getattr(response, "status_code", None)
    if status is None:
        return None
    try:
        return int(status)
    except (TypeError, ValueError):
        return None


def _error_text(exc: Exception) -> str:
    """Everything the provider said about the failure, flattened."""

    parts = [str(exc)]
    body = getattr(exc, "body", None)
    if body:
        parts.append(str(body))
    response = getattr(exc, "response", None)
    if response is not None:
        text = getattr(response, "text", None)
        if text:
            parts.append(str(text))
    return " ".join(parts).lower()


def _is_quota_exhausted(exc: Exception) -> bool:
    """Whether the provider refused because a quota window is exhausted.

    <p>A quota that is spent for the day (or the month) will not free
    up by trying again in two seconds — the window has to reset first.
    Retrying then only adds delay to a call that was already going to
    fail.
    """

    text = _error_text(exc)
    return any(marker in text for marker in _QUOTA_MARKERS)


def _retry_after_hint(exc: Exception) -> float | None:
    """How long the provider asked us to wait, in seconds.

    <p>Two sources are consulted: the standard {@code Retry-After}
    header, and the "Please try again in 17m14.208s." sentence the
    OpenAI-compatible providers put in the error body.
    """

    response = getattr(exc, "response", None)
    headers = getattr(response, "headers", None)
    if headers:
        raw = headers.get("retry-after") or headers.get("Retry-After")
        if raw:
            try:
                return float(raw)
            except (TypeError, ValueError):
                pass

    match = _RETRY_AFTER_PATTERN.search(_error_text(exc))
    if not match:
        return None
    try:
        value = float(match.group(1))
    except (TypeError, ValueError):
        return None
    unit = match.group(2).lower()
    return value * _UNIT_SECONDS.get(unit, 1.0)


def _retry_delay(exc: Exception, attempt: int) -> float | None:
    """How long to wait before the next attempt, or {@code None} to give up.

    <p>Three cases are distinguished:

    <ol>
    <li>The provider named a moment when it will accept traffic again
    <b>within</b> the wait budget — that moment is honoured.</li>
    <li>The provider named a moment <b>beyond</b> the wait budget, or
    the failure is a spent quota — retrying cannot help, so the error
    is raised immediately instead of burning the caller's timeout on
    sleeps.</li>
    <li>The provider said nothing useful — the plain exponential
    backoff applies.</li>
    </ol>
    """

    if _is_quota_exhausted(exc):
        return None

    hint = _retry_after_hint(exc)
    if hint is not None:
        if hint > MAX_BACKOFF:
            return None
        return max(hint, 0.0)

    return min(MAX_BACKOFF, BASE_BACKOFF * (2 ** attempt))
