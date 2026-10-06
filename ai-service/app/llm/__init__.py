"""Public LLM abstraction.

The service exposes a single `LLMClient` whose behaviour is determined by
the `LLM_PROVIDER` environment variable. Supported providers:

* ``openai`` — uses the OpenAI Python SDK.
* ``anthropic`` — uses the Anthropic Python SDK.
* ``ollama`` — calls a local Ollama HTTP server.
* ``mock`` — deterministic offline stand-in (default).

A ``mock`` provider is enough to develop and test end-to-end; the rest only
require API credentials to be set.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Iterable, List, Optional

from .providers import (
    AnthropicProvider,
    MockProvider,
    OllamaProvider,
    OpenAIProvider,
)
from .schemas import ChatMessage, GenerationRequest, GenerationResponse


@dataclass
class LLMClient:
    """High-level wrapper that picks the right provider for the environment."""

    provider_name: str = "mock"
    api_key: str = ""
    api_base: str = ""
    model: str = "gpt-4o-mini"
    max_tokens: int = 2048
    temperature: float = 0.2
    timeout_seconds: float = 60.0

    def __post_init__(self) -> None:
        self.provider = self._build_provider()

    def _build_provider(self):
        name = (self.provider_name or "mock").lower()
        if name == "openai":
            return OpenAIProvider(api_key=self.api_key, base_url=self.api_base or None,
                                  timeout=self.timeout_seconds)
        if name == "anthropic":
            return AnthropicProvider(api_key=self.api_key, base_url=self.api_base or None,
                                     timeout=self.timeout_seconds)
        if name == "ollama":
            return OllamaProvider(base_url=self.api_base or "http://localhost:11434",
                                  timeout=self.timeout_seconds)
        return MockProvider()

    def complete(self, messages: Iterable[ChatMessage], *,
                 model: Optional[str] = None,
                 temperature: Optional[float] = None,
                 max_tokens: Optional[int] = None,
                 stop: Optional[List[str]] = None) -> GenerationResponse:
        req = GenerationRequest(
            messages=list(messages),
            model=model or self.model,
            temperature=self.temperature if temperature is None else temperature,
            max_tokens=self.max_tokens if max_tokens is None else max_tokens,
            stop=stop or [],
        )
        return self.provider.complete(req)

    # ------------------------------------------------------------------
    # helpers used by the documentation / drift endpoints
    # ------------------------------------------------------------------

    def generate_documentation(self, *, system_prompt: str, user_prompt: str) -> GenerationResponse:
        return self.complete([
            ChatMessage(role="system", content=system_prompt),
            ChatMessage(role="user", content=user_prompt),
        ])

    def is_live(self) -> bool:
        """Whether this client talks to a real provider (vs. mock)."""

        return (self.provider_name or "mock").lower() != "mock"


def client_from_env() -> LLMClient:
    return LLMClient(
        provider_name=os.getenv("LLM_PROVIDER", "mock"),
        api_key=os.getenv("LLM_API_KEY", ""),
        api_base=os.getenv("LLM_API_BASE", ""),
        model=os.getenv("LLM_MODEL", "gpt-4o-mini"),
        max_tokens=int(os.getenv("LLM_MAX_TOKENS", "2048")),
        temperature=float(os.getenv("LLM_TEMPERATURE", "0.2")),
        timeout_seconds=float(os.getenv("LLM_TIMEOUT_SECONDS", "60")),
    )