"""OpenAI provider (also used for OpenAI-compatible endpoints such as Groq)."""

from __future__ import annotations

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
                last_error = exc
                delay = min(MAX_BACKOFF, BASE_BACKOFF * (2 ** attempt))
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

    status = getattr(exc, "status_code", None)
    if status is None:
        response = getattr(exc, "response", None)
        status = getattr(response, "status_code", None)
    if status is None:
        return False
    return status == 429 or 500 <= int(status) < 600
