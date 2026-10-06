"""Lightweight in-memory embedding store + similarity search.

For local development and tests, a deterministic mock embedder produces
embeddings derived from a hash of the input text. For production use a
real embedding model (OpenAI, Ollama, ...) by implementing the same
``EmbeddingProvider`` interface.
"""

from __future__ import annotations

import hashlib
import math
import re
import uuid
from dataclasses import dataclass, field
from typing import Iterable, List, Sequence


_TOKEN_RE = re.compile(r"[A-Za-z0-9_]+")


def _tokenise(text: str) -> List[str]:
    return [t.lower() for t in _TOKEN_RE.findall(text or "")]


def _hash_token(token: str, dim: int) -> int:
    h = hashlib.sha256(token.encode("utf-8")).digest()
    return int.from_bytes(h[:4], "big") % dim


@dataclass
class EmbeddingVector:
    values: List[float]

    def cosine(self, other: "EmbeddingVector") -> float:
        if not self.values or not other.values:
            return 0.0
        dot = sum(a * b for a, b in zip(self.values, other.values))
        norm_a = math.sqrt(sum(a * a for a in self.values))
        norm_b = math.sqrt(sum(b * b for b in other.values))
        if norm_a == 0 or norm_b == 0:
            return 0.0
        return dot / (norm_a * norm_b)


class EmbeddingProvider:
    """Default mock embedder (no external dependencies)."""

    name = "mock"

    def __init__(self, *, dimensions: int = 256) -> None:
        self.dimensions = dimensions

    def embed(self, text: str) -> EmbeddingVector:
        vec = [0.0] * self.dimensions
        for token in _tokenise(text):
            idx = _hash_token(token, self.dimensions)
            vec[idx] += 1.0
        norm = math.sqrt(sum(v * v for v in vec))
        if norm > 0:
            vec = [v / norm for v in vec]
        return EmbeddingVector(values=vec)


@dataclass
class StoredDocument:
    """A single chunk indexed in the vector store."""

    id: str
    text: str
    embedding: EmbeddingVector
    metadata: dict = field(default_factory=dict)


class VectorStore:
    """In-memory vector store. Use this for tests + dev; swap for pgvector in prod."""

    def __init__(self, embedder: EmbeddingProvider | None = None) -> None:
        self.embedder = embedder or EmbeddingProvider()
        self._docs: dict[str, StoredDocument] = {}

    def upsert(self, *, text: str, metadata: dict | None = None, doc_id: str | None = None) -> StoredDocument:
        did = doc_id or str(uuid.uuid4())
        embedding = self.embedder.embed(text)
        doc = StoredDocument(id=did, text=text, embedding=embedding, metadata=dict(metadata or {}))
        self._docs[did] = doc
        return doc

    def delete(self, doc_id: str) -> None:
        self._docs.pop(doc_id, None)

    def search(self, query: str, *, top_k: int = 5) -> List[tuple[StoredDocument, float]]:
        qvec = self.embedder.embed(query)
        scored = [(doc, qvec.cosine(doc.embedding)) for doc in self._docs.values()]
        scored.sort(key=lambda pair: pair[1], reverse=True)
        return scored[:top_k]

    def clear(self) -> None:
        self._docs.clear()

    def __len__(self) -> int:
        return len(self._docs)


def chunk_markdown(markdown: str, *, max_chars: int = 800) -> List[str]:
    """Split a markdown document into retrieval-friendly chunks by sections."""

    chunks: List[str] = []
    buffer: List[str] = []
    for line in markdown.split("\n"):
        if line.startswith("#") and buffer:
            chunks.append("\n".join(buffer).strip())
            buffer = [line]
            continue
        buffer.append(line)
        if sum(len(l) for l in buffer) >= max_chars:
            chunks.append("\n".join(buffer).strip())
            buffer = []
    if buffer:
        chunks.append("\n".join(buffer).strip())
    return [c for c in chunks if c]