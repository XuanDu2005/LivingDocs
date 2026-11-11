"""Knowledge-base retrieval service backed by the in-memory vector store."""

from __future__ import annotations

from dataclasses import dataclass
from typing import List, Optional

from ..rag import StoredDocument, VectorStore, chunk_markdown


@dataclass
class RetrievalHit:
    """A single hit returned by the knowledge base."""

    doc_id: str
    text: str
    score: float
    metadata: dict

    def to_dict(self) -> dict:
        return {
            "doc_id": self.doc_id,
            "text": self.text,
            "score": round(self.score, 4),
            "metadata": dict(self.metadata),
        }


class KnowledgeBase:
    """Indexes Markdown documents and serves similarity search."""

    def __init__(self, store: Optional[VectorStore] = None) -> None:
        self.store = store or VectorStore()

    def index_document(self, *, document_id: str, title: str, body: str,
                      doc_type: str = "DOCUMENT",
                      workspace_id: Optional[str] = None,
                      metadata: Optional[dict] = None) -> int:
        chunks = chunk_markdown(body)
        for i, chunk in enumerate(chunks):
            md = dict(metadata or {})
            md.update({
                "document_id": document_id,
                "title": title,
                "doc_type": doc_type,
                "workspace_id": workspace_id,
                "chunk_index": i,
            })
            self.store.upsert(text=chunk, metadata=md,
                              doc_id=f"{document_id}:{i}")
        return len(chunks)

    def remove_document(self, document_id: str) -> int:
        """Remove every chunk of a document from the vector store.

        <p>The store is backed by PostgreSQL + pgvector, so the chunks are
        deleted with a single prefix query. It used to walk an in-memory
        ``_docs`` dictionary that the store no longer has — every call
        raised ``AttributeError``, which surfaced as a 500 on the
        ``DELETE /knowledge/index/{id}`` endpoint and left stale chunks
        in the index forever.
        """
        return self.store.delete_document(document_id)

    def search(self, *, query: str, workspace_id: Optional[str] = None,
               top_k: int = 5) -> List[RetrievalHit]:
        # The workspace scope is pushed down into SQL. Filtering in Python
        # after the LIMIT would take the globally closest chunks first and
        # then discard the ones from other workspaces — leaking another
        # tenant's content whenever the top-k is shared between them.
        results = self.store.search(query, top_k=top_k, workspace_id=workspace_id)
        hits: List[RetrievalHit] = []
        for doc, score in results:
            if workspace_id and doc.metadata.get("workspace_id") != workspace_id:
                continue
            hits.append(RetrievalHit(doc_id=doc.id, text=doc.text,
                                     score=score, metadata=doc.metadata))
        return hits

    def reset(self) -> None:
        self.store.clear()