"""LLM provider implementations.

Each provider implements the same tiny surface:
    ``complete(request: GenerationRequest) -> GenerationResponse``

The OpenAI / Anthropic providers lazily import their SDK so the service can
still boot (and tests can run) when those libraries are not installed.
"""

from __future__ import annotations

from .base import LLMProviderBase  # noqa: F401
from .mock_provider import MockProvider
from .openai_provider import OpenAIProvider
from .anthropic_provider import AnthropicProvider
from .ollama_provider import OllamaProvider

__all__ = [
    "LLMProviderBase",
    "MockProvider",
    "OpenAIProvider",
    "AnthropicProvider",
    "OllamaProvider",
]