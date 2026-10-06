"""Anthropic provider."""

from __future__ import annotations

from ..schemas import GenerationRequest, GenerationResponse
from .base import LLMProviderBase


class AnthropicProvider(LLMProviderBase):
    name = "anthropic"

    def __init__(self, *, api_key: str, base_url: str | None = None, timeout: float = 60.0) -> None:
        if not api_key:
            raise ValueError("AnthropicProvider requires an api_key")
        try:
            from anthropic import Anthropic  # type: ignore
        except ImportError as e:  # pragma: no cover
            raise RuntimeError("anthropic SDK not installed") from e
        self.client = Anthropic(api_key=api_key, base_url=base_url, timeout=timeout)

    def complete(self, request: GenerationRequest) -> GenerationResponse:
        system_parts = [m.content for m in request.messages if m.role == "system"]
        user_parts = [{"role": m.role, "content": m.content}
                      for m in request.messages if m.role != "system"]
        resp = self.client.messages.create(
            model=request.model,
            max_tokens=request.max_tokens,
            system="\n".join(system_parts) or None,
            messages=user_parts,
            temperature=request.temperature,
        )
        text = "".join(getattr(block, "text", "") for block in resp.content)
        usage = getattr(resp, "usage", None)
        return GenerationResponse(
            text=text,
            model=request.model,
            input_tokens=getattr(usage, "input_tokens", 0) if usage else 0,
            output_tokens=getattr(usage, "output_tokens", 0) if usage else 0,
            provider=self.name,
            raw=None,
        )