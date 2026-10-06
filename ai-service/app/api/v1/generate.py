"""POST /generate — generate documentation from parsed source."""

from fastapi import APIRouter

from ...llm import LLMClient, client_from_env
from ...services import DocumentationGenerator
from .schemas import (
    GenerateDocumentationRequest,
    GenerateDocumentationResponse,
    GroundingSource,
)

router = APIRouter()

_llm: LLMClient | None = None


def get_llm() -> LLMClient:
    global _llm
    if _llm is None:
        _llm = client_from_env()
    return _llm


@router.post("", response_model=GenerateDocumentationResponse)
def generate(req: GenerateDocumentationRequest) -> GenerateDocumentationResponse:
    generator = DocumentationGenerator(llm=get_llm())
    result = generator.generate(
        files=[(f.path, f.content) for f in req.files],
        template=req.template,
        focus=req.focus,
        language_hint=req.language_hint,
    )
    return GenerateDocumentationResponse(
        markdown=result.markdown,
        confidence=result.confidence,
        model=result.model,
        sources=[GroundingSource(**s) for s in result.sources],
        notes=result.notes,
    )