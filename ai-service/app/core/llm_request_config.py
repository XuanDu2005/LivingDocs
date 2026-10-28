"""Per-request LLM configuration resolved from the backend's request headers.

The LivingDocs backend owns the workspace-scoped AI configuration: which
provider a workspace uses, which model, which API key. That data lives in
PostgreSQL and never reaches this service through the environment, so the
backend forwards it on every call via the ``X-Ai-*`` headers.

This module turns those headers into an :class:`LLMClient` so the whole
platform can honour a per-workspace provider without redeploying the AI
service.

Security model
--------------
The backend also sends ``X-Ai-Internal-Token``. When the service is
configured with an internal token, requests that do not present a matching
token are rejected with 401 — a per-workspace API key is therefore never
exposed to an untrusted caller who can reach the service directly.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Optional

from ..llm import LLMClient

#: Header names used by the backend to forward the workspace AI config.
HEADER_PROVIDER = "X-Ai-Provider"
HEADER_MODEL = "X-Ai-Model"
HEADER_BASE_URL = "X-Ai-Base-Url"
HEADER_TEMPERATURE = "X-Ai-Temperature"
HEADER_MAX_TOKENS = "X-Ai-Max-Tokens"
HEADER_API_KEY = "X-Ai-Api-Key"
HEADER_INTERNAL_TOKEN = "X-Ai-Internal-Token"
HEADER_WORKSPACE_ID = "X-Workspace-Id"


def internal_token() -> str:
    """The shared secret this service expects on every backend call."""

    return os.getenv("AI_SERVICE_INTERNAL_TOKEN", "").strip()


@dataclass
class RequestLLMConfig:
    """Effective LLM configuration for a single request."""

    provider_name: str
    api_key: str
    api_base: str
    model: str
    temperature: float
    max_tokens: int

    def to_client(self) -> LLMClient:
        return LLMClient(
            provider_name=self.provider_name,
            api_key=self.api_key,
            api_base=self.api_base,
            model=self.model,
            temperature=self.temperature,
            max_tokens=self.max_tokens,
        )


def _as_float(raw: Optional[str], fallback: float) -> float:
    if raw is None or raw == "":
        return fallback
    try:
        return float(raw)
    except (TypeError, ValueError):
        return fallback


def _as_int(raw: Optional[str], fallback: int) -> int:
    if raw is None or raw == "":
        return fallback
    try:
        return int(raw)
    except (TypeError, ValueError):
        return fallback


def config_from_headers(headers, *, fallback: RequestLLMConfig) -> RequestLLMConfig:
    """Overlay the request headers on top of the environment defaults.

    The headers always win: when the backend forwards a workspace's
    configuration we must not silently fall back to the container's
    global provider.
    """

    def get(name: str) -> Optional[str]:
        value = headers.get(name)
        if value is None:
            return None
        value = value.strip()
        return value or None

    provider = get(HEADER_PROVIDER)
    model = get(HEADER_MODEL)
    base_url = get(HEADER_BASE_URL)
    api_key = get(HEADER_API_KEY)
    temperature = get(HEADER_TEMPERATURE)
    max_tokens = get(HEADER_MAX_TOKENS)

    return RequestLLMConfig(
        provider_name=(provider or fallback.provider_name).lower(),
        api_key=api_key if api_key is not None else fallback.api_key,
        api_base=base_url if base_url is not None else fallback.api_base,
        model=model or fallback.model,
        temperature=_as_float(temperature, fallback.temperature),
        max_tokens=_as_int(max_tokens, fallback.max_tokens),
    )


def default_config() -> RequestLLMConfig:
    """The environment-level configuration, used when no headers arrive."""

    return RequestLLMConfig(
        provider_name=os.getenv("LLM_PROVIDER", "mock").lower(),
        api_key=os.getenv("LLM_API_KEY", ""),
        api_base=os.getenv("LLM_API_BASE", ""),
        model=os.getenv("LLM_MODEL", "gpt-4o-mini"),
        temperature=float(os.getenv("LLM_TEMPERATURE", "0.2")),
        max_tokens=int(os.getenv("LLM_MAX_TOKENS", "2048")),
    )
