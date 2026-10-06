"""Core domain services exposed to the FastAPI layer.

Modules:
    ast      — code parsing
    llm      — provider abstraction
    rag      — embeddings + vector store
    docs     — documentation generation, drift detection, retrieval
"""

from .generator import DocumentationGenerator, GenerationResult
from .drift import DriftDetector, DriftReport, DriftFinding
from .retrieval import KnowledgeBase

__all__ = [
    "DocumentationGenerator",
    "GenerationResult",
    "DriftDetector",
    "DriftReport",
    "DriftFinding",
    "KnowledgeBase",
]