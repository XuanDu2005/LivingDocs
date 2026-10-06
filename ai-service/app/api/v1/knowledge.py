"""Knowledge-base endpoints (index + search)."""

from fastapi import APIRouter

from ...services import KnowledgeBase
from .schemas import (
    IndexDocumentRequest,
    IndexDocumentResponse,
    KnowledgeHit,
    KnowledgeSearchRequest,
    KnowledgeSearchResponse,
)

router = APIRouter()

_kb: KnowledgeBase | None = None


def get_kb() -> KnowledgeBase:
    global _kb
    if _kb is None:
        _kb = KnowledgeBase()
    return _kb


@router.post("/index", response_model=IndexDocumentResponse)
def index_document(req: IndexDocumentRequest) -> IndexDocumentResponse:
    n = get_kb().index_document(
        document_id=req.document_id,
        title=req.title,
        body=req.body,
        doc_type=req.doc_type,
        workspace_id=req.workspace_id,
        metadata=req.metadata,
    )
    return IndexDocumentResponse(chunks_indexed=n)


@router.delete("/index/{document_id}")
def delete_document(document_id: str) -> dict:
    removed = get_kb().remove_document(document_id)
    return {"removed": removed}


@router.post("/search", response_model=KnowledgeSearchResponse)
def search(req: KnowledgeSearchRequest) -> KnowledgeSearchResponse:
    hits = get_kb().search(query=req.query, workspace_id=req.workspace_id, top_k=req.top_k)
    return KnowledgeSearchResponse(hits=[KnowledgeHit(**h.to_dict()) for h in hits])