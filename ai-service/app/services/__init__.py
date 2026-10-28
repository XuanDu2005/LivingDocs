"""Core domain services exposed to the FastAPI layer.

Modules:
    ast           — code parsing
    llm           — provider abstraction
    rag           — embeddings + vector store
    generator     — documentation generation
    drift         — AST / heuristic drift detection
    semantic_drift— LLM-assisted semantic drift detection
    diffing       — unified diff between two source trees
    retrieval     — knowledge base
"""

from .generator import DocumentationGenerator, GenerationResult
from .drift import DriftDetector, DriftReport, DriftFinding
from .semantic_drift import SemanticDriftAnalyzer, SemanticDriftResult, SemanticFinding
from .diffing import unified_diff
from .retrieval import KnowledgeBase

__all__ = [
    "DocumentationGenerator",
    "GenerationResult",
    "DriftDetector",
    "DriftReport",
    "DriftFinding",
    "SemanticDriftAnalyzer",
    "SemanticDriftResult",
    "SemanticFinding",
    "unified_diff",
    "KnowledgeBase",
]
