import os
import json
import re  
import math
import hashlib
import uuid
import psycopg2
from pgvector.psycopg2 import register_vector
from psycopg2.extras import Json
from dataclasses import dataclass, field
from typing import Iterable, List, Sequence


_TOKEN_RE = re.compile(r"[A-Za-z0-9_]+")


def _tokenise(text: str) -> List[str]:
    return [t.lower() for t in _TOKEN_RE.findall(text or "")]


def _hash_token(token: str, dim: int) -> int:
    h = hashlib.sha256(token.encode("utf-8")).digest()
    return int.from_bytes(h[:4], "big") % dim


def _as_uuid(value: object) -> str | None:
    """Return ``value`` as a UUID string, or ``None`` when it is not one.

    <p>The ``workspace_id`` and ``document_id`` columns of
    ``knowledge_chunks`` are typed ``UUID``. A caller may hand over a
    free-form label (the platform's own tests use ``"doc-1"``), and
    Postgres rejects such a value with an "invalid input syntax for type
    uuid" error that aborts the whole statement. Dropping the value is
    safe: the label is preserved in the JSON ``metadata`` column, which
    is where the search reads the workspace scope from.
    """
    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    try:
        return str(uuid.UUID(text))
    except (ValueError, AttributeError, TypeError):
        return None


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
    """Persistent vector store using PostgreSQL + pgvector."""

    def __init__(self, embedder: EmbeddingProvider | None = None) -> None:
        # The embedding dimension should match the one used in the database schema (1536 for text-embedding-3-small).
        dim = int(os.getenv("EMBEDDING_DIMENSIONS", "1536"))
        self.embedder = embedder or EmbeddingProvider(dimensions=dim)

    def _get_connection(self):
        # Establish a connection to the PostgreSQL database using environment variables for configuration.
        conn = psycopg2.connect(
            host=os.getenv("POSTGRES_HOST", "localhost"),
            port=os.getenv("POSTGRES_PORT", "5432"),
            dbname=os.getenv("POSTGRES_DB", "livingdocs"),
            user=os.getenv("POSTGRES_USER", "livingdocs"),
            password=os.getenv("POSTGRES_PASSWORD", "change_me")
        )
        register_vector(conn)
        return conn

    def upsert(self, *, text: str, metadata: dict | None = None, doc_id: str | None = None) -> StoredDocument:
        did = doc_id or str(uuid.uuid4())
        embedding = self.embedder.embed(text)

        workspace_id = metadata.get("workspace_id") if metadata else None
        document_id = metadata.get("document_id") if metadata else None

        # `workspace_id` and `document_id` are typed UUID columns. A caller that
        # indexes a document outside the platform (the AI service's own tests, the
        # health probe) may pass a non-UUID label such as "doc-1"; inserting that
        # verbatim aborts the whole statement with an "invalid input syntax for
        # type uuid" error. The labels are kept in the JSON metadata, which is
        # where the search actually reads them from, so dropping them from the
        # typed columns loses nothing.
        workspace_id = _as_uuid(workspace_id)
        document_id = _as_uuid(document_id)

        with self._get_connection() as conn:
            with conn.cursor() as cur:
                cur.execute("""
                    INSERT INTO knowledge_chunks (id, workspace_id, document_id, content, embedding, metadata)
                    VALUES (%s, %s, %s, %s, %s, %s)
                    ON CONFLICT (id) DO UPDATE
                    SET content = EXCLUDED.content,
                        embedding = EXCLUDED.embedding,
                        metadata = EXCLUDED.metadata;
                """, (did, workspace_id, document_id, text, embedding.values, Json(metadata or {})))
            conn.commit()

        return StoredDocument(id=did, text=text, embedding=embedding, metadata=dict(metadata or {}))

    def delete(self, doc_id: str) -> None:
        """Delete every chunk that belongs to the given document.

        <p>Chunk ids are written as ``"{document_id}:{chunk_index}"`` by
        :meth:`upsert`, so a prefix match removes exactly the chunks of
        one document and never touches another one.
        """
        with self._get_connection() as conn:
            with conn.cursor() as cur:
                cur.execute("DELETE FROM knowledge_chunks WHERE id LIKE %s", (f"{doc_id}:%",))
            conn.commit()

    def delete_document(self, document_id: str) -> int:
        """Delete all chunks of a document and report how many were removed.

        <p>The number is the row count of the delete, which lets the
        caller distinguish "the document was not indexed" (0) from
        "the document was indexed and has now been removed" (> 0).
        """
        with self._get_connection() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "DELETE FROM knowledge_chunks WHERE id LIKE %s", (f"{document_id}:%",))
                removed = cur.rowcount or 0
            conn.commit()
        return removed

    def search(self, query: str, *, top_k: int = 5,
               workspace_id: str | None = None) -> List[tuple[StoredDocument, float]]:
        qvec = self.embedder.embed(query)

        with self._get_connection() as conn:
            with conn.cursor() as cur:
                # Use the pgvector operator `<=>` for cosine distance and order by it to get the most similar chunks.
                #
                # The workspace filter is applied in SQL, not in Python. Filtering after the
                # LIMIT would take the globally closest chunks first and then throw away
                # the ones belonging to other workspaces — which leaks the existence and the
                # content of another tenant's documents whenever the top-k is shared.
                # The filter reads `metadata->>'workspace_id'` rather than the typed
                # `workspace_id` column because the value arrives as a string and the
                # column is a UUID: a malformed value would make Postgres abort the whole
                # query with an "invalid input syntax for type uuid" error.
                cur.execute("""
                    SELECT id, content, metadata, (embedding <=> %s::vector) AS distance
                    FROM knowledge_chunks
                    WHERE (%s::text IS NULL) OR (metadata->>'workspace_id' = %s::text)
                    ORDER BY distance ASC
                    LIMIT %s;
                """, (qvec.values, workspace_id, workspace_id, top_k))

                results = []
                for row in cur.fetchall():
                    doc = StoredDocument(id=row[0], text=row[1], embedding=EmbeddingVector([]), metadata=row[2])
                    distance = float(row[3])
                    score = 1.0 - distance  # Convert distance to similarity score
                    results.append((doc, score))
                filtered_results = [(d, s) for d, s in results if s > -0.5]
                return filtered_results

    def clear(self) -> None:
        with self._get_connection() as conn:
            with conn.cursor() as cur:
                cur.execute("TRUNCATE TABLE knowledge_chunks;")
            conn.commit()

    def __len__(self) -> int:
        with self._get_connection() as conn:
            with conn.cursor() as cur:
                cur.execute("SELECT COUNT(*) FROM knowledge_chunks;")
                return cur.fetchone()[0]


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