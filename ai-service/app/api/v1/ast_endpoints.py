"""AST endpoints — parse a single source file and return extracted entities."""

from fastapi import APIRouter, HTTPException

from ...ast import analyser_for, supported_languages
from .schemas import ParseRequest, ParseResponse

router = APIRouter()


@router.get("/languages")
def languages() -> dict:
    return {"languages": supported_languages()}


@router.post("/parse", response_model=ParseResponse)
def parse(req: ParseRequest) -> ParseResponse:
    analyser = analyser_for(_detect_language(req.path))
    if analyser is None:
        raise HTTPException(status_code=400,
                            detail=f"No analyser available for {req.path}. "
                                   f"Supported: {supported_languages()}")
    parsed = analyser(req.path, req.content)
    return ParseResponse(
        language=parsed.language,
        entities=[e.to_dict() for e in parsed.entities],
        imports=parsed.imports,
        module_docstring=parsed.module_docstring,
    )


def _detect_language(path: str) -> str:
    if path.endswith(".py"):
        return "python"
    if path.endswith((".ts", ".tsx")):
        return "typescript"
    if path.endswith((".js", ".jsx", ".mjs", ".cjs")):
        return "javascript"
    if path.endswith(".java"):
        return "java"
    return ""