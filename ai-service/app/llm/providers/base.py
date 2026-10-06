"""Provider base class."""

from __future__ import annotations

from abc import ABC, abstractmethod

from ..schemas import GenerationRequest, GenerationResponse


class LLMProviderBase(ABC):
    """Interface every concrete provider must implement."""

    name: str = "base"

    @abstractmethod
    def complete(self, request: GenerationRequest) -> GenerationResponse:  # pragma: no cover
        raise NotImplementedError