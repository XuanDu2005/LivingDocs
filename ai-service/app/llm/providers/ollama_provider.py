"""Ollama provider (local LLM server)."""

from __future__ import annotations

import json

import httpx

from ..schemas import GenerationRequest, GenerationResponse
from .base import LLMProviderBase


class OllamaProvider(LLMProviderBase):
    name = "ollama"

    def __init__(self, *, base_url: str = "http://localhost:11434", timeout: float = 60.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def complete(self, request: GenerationRequest) -> GenerationResponse:
        payload = {
            "model": request.model,
            "messages": [{"role": m.role, "content": m.content} for m in request.messages],
            "options": {
                "temperature": request.temperature,
                "num_predict": request.max_tokens,
            },
            "stream": False,
        }
        with httpx.Client(timeout=self.timeout) as client:
            r = client.post(f"{self.base_url}/api/chat", json=payload)
            r.raise_for_status()
            data = r.json()
        text = "".join((m.get("content") or "") for m in data.get("messages", []))
        return GenerationResponse(
            text=text,
            model=request.model,
            input_tokens=data.get("prompt_eval_count", 0),
            output_tokens=data.get("eval_count", 0),
            provider=self.name,
            raw=data,
        )