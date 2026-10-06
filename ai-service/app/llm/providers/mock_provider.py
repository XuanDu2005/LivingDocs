"""Offline / test provider used when no real LLM credentials are available."""

from __future__ import annotations

import hashlib
from datetime import datetime
from typing import Iterable

from ..schemas import ChatMessage, GenerationRequest, GenerationResponse
from .base import LLMProviderBase


class MockProvider(LLMProviderBase):
    name = "mock"

    def __init__(self, *, deterministic: bool = True) -> None:
        self.deterministic = deterministic

    def complete(self, request: GenerationRequest) -> GenerationResponse:
        joined = "\n".join(m.content for m in request.messages)
        seed = hashlib.sha256(joined.encode("utf-8")).hexdigest()
        text = self._compose_text(joined, seed)
        return GenerationResponse(
            text=text,
            model=request.model,
            input_tokens=max(1, len(joined) // 4),
            output_tokens=max(1, len(text) // 4),
            provider=self.name,
            raw={"seed": seed, "ts": datetime.utcnow().isoformat()},
        )

    # ------------------------------------------------------------------
    # Mock content generation
    # ------------------------------------------------------------------

    def _compose_text(self, joined: str, seed: str) -> str:
        lowered = joined.lower()
        if "documentation" in lowered or "generate" in lowered:
            return self._doc_template(seed)
        if "drift" in lowered:
            return self._drift_template(seed)
        if "summary" in lowered or "summarize" in lowered:
            return self._summary_template(seed)
        return f"# LivingDocs response\n\nSeed: {seed[:16]}\n\nThis is a mock completion produced without contacting an external provider."

    def _doc_template(self, seed: str) -> str:
        return (
            "# Generated documentation (mock)\n\n"
            "_This draft was produced by the offline mock provider. "
            "Configure an LLM provider (openai, anthropic, ollama) to get "
            "real prose._\n\n"
            f"- Seed: `{seed[:12]}`\n"
            "- Confidence: 0.82\n\n"
            "## Overview\n\n"
            "The component described in the prompt has been summarised below. "
            "Reviewers should verify the claims against the source code.\n\n"
            "## Public API\n\n"
            "- TODO list the functions / classes discovered by the AST analyser.\n\n"
            "## Notes\n\n"
            "- This is a placeholder. Replace with real content in production.\n"
        )

    def _drift_template(self, seed: str) -> str:
        return (
            '{"drift_kind":"REFERENTIAL","severity":"HIGH","title":"Outdated reference (mock)",'
            '"description":"The mock provider did not perform a real comparison. Provide an LLM '
            'provider to detect drift accurately.","confidence":0.42,'
            '"evidence":{"seed":"' + seed[:16] + '"}}'
        )

    def _summary_template(self, seed: str) -> str:
        return f"Summary (mock, seed {seed[:8]}):\n\n- Key insight A\n- Key insight B"