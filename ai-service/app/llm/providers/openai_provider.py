"""OpenAI provider."""

from __future__ import annotations

from ..schemas import GenerationRequest, GenerationResponse
from .base import LLMProviderBase


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
        resp = self.client.chat.completions.create(
            model=request.model,
            messages=messages,
            temperature=request.temperature,
            max_tokens=request.max_tokens,
            stop=request.stop or None,
        )
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