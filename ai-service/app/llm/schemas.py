"""Pydantic schemas shared by the LLM providers."""

from __future__ import annotations

from typing import List, Optional

from pydantic import BaseModel, Field


class ChatMessage(BaseModel):
    role: str = Field(description="system | user | assistant | tool")
    content: str


class GenerationRequest(BaseModel):
    messages: List[ChatMessage]
    model: str = "gpt-4o-mini"
    temperature: float = 0.2
    max_tokens: int = 2048
    stop: List[str] = Field(default_factory=list)
    metadata: dict = Field(default_factory=dict)


class GenerationResponse(BaseModel):
    text: str
    model: str
    input_tokens: int = 0
    output_tokens: int = 0
    provider: str = "unknown"
    raw: Optional[dict] = None

    @property
    def ok(self) -> bool:
        return bool(self.text)