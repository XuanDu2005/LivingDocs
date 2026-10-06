"""AST-based code entity extraction for LivingDocs.

The LivingDocs analyser needs a uniform shape for code entities regardless
of the underlying parser library. This package ships lightweight, dependency-free
analysers for the most common languages. More languages can be added by
implementing the same interface.

Public API:
    analyser_for(language) -> CodeAnalyser
    CodeEntity dataclass
"""

from .models import (
    CodeEntity,
    CodeEntityKind,
    ParsedFile,
    FunctionParameter,
)
from .registry import analyser_for, supported_languages, register_analyser
from . import _builtin  # noqa: F401  (registers built-in analysers)

__all__ = [
    "CodeEntity",
    "CodeEntityKind",
    "FunctionParameter",
    "ParsedFile",
    "analyser_for",
    "supported_languages",
    "register_analyser",
]