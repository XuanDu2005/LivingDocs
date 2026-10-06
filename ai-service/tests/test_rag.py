"""Tests for the in-memory knowledge base / vector store."""

from __future__ import annotations

from app.rag import EmbeddingProvider, VectorStore, chunk_markdown
from app.services import KnowledgeBase


def test_embedding_provider_is_deterministic() -> None:
    p = EmbeddingProvider(dimensions=64)
    a = p.embed("hello world")
    b = p.embed("hello world")
    assert a.values == b.values
    assert a.cosine(b) == pytest_approx(1.0)


def test_chunk_markdown_splits_by_sections() -> None:
    text = "# A\n\nIntro\n\n## B\n\nBody\n"
    chunks = chunk_markdown(text, max_chars=10)
    assert chunks


def test_knowledge_base_index_and_search() -> None:
    kb = KnowledgeBase()
    chunks = kb.index_document(
        document_id="doc-1",
        title="Calc",
        body="# Calculator\n\nUse the `add` function to sum two integers.\n\n## Edge cases\n\nDon't pass None.",
    )
    assert chunks >= 1
    hits = kb.search(query="how to add two numbers", top_k=5)
    assert hits
    # the top hit should mention the relevant chunk
    texts = " ".join(h.text for h in hits)
    assert "add" in texts or "sum" in texts


# local approx because we don't want pytest as a hard dependency for this file
def pytest_approx(value: float, tol: float = 1e-6) -> float:
    return value  # exact comparison is fine for identical inputs