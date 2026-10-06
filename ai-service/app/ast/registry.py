"""Registry that maps a file extension / language id to a CodeAnalyser."""

from __future__ import annotations

from typing import Callable, Dict, List, Type

from .models import ParsedFile

#: Signature every analyser must implement.
AnalyserFn = Callable[[str, str], ParsedFile]


class _Registry:
    """Tiny singleton-style registry."""

    def __init__(self) -> None:
        self._by_language: Dict[str, AnalyserFn] = {}

    def register(self, language: str, fn: AnalyserFn) -> None:
        self._by_language[language.lower()] = fn

    def get(self, language: str) -> AnalyserFn | None:
        return self._by_language.get(language.lower())

    def languages(self) -> List[str]:
        return sorted(self._by_language.keys())


_REGISTRY = _Registry()


def register_analyser(language: str) -> Callable[[AnalyserFn], AnalyserFn]:
    """Decorator for plugging an analyser into the registry."""

    def decorator(fn: AnalyserFn) -> AnalyserFn:
        _REGISTRY.register(language, fn)
        return fn

    return decorator


def analyser_for(language: str) -> AnalyserFn | None:
    """Return the analyser registered for the given language, or None."""

    return _REGISTRY.get(language)


def supported_languages() -> List[str]:
    return _REGISTRY.languages()