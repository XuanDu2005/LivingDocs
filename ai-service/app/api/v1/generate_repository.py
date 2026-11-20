"""POST /generate/repository — generate overview documentation for a whole repository.

The regular ``/generate`` endpoint documents a set of source files. This
one documents a *repository*: it receives a structured snapshot of the
project (tree, language mix, dependency manifests, entry points, a sample
of the source files) and writes an overview document — a README, an
architecture guide, an onboarding guide or an API reference.

The snapshot is sent as structure rather than as a flat file list on
purpose: the AST analysers cannot read a {@code package.json}, and a
document that describes "how to install and run this project" has to be
grounded on the manifest, not on a class.
"""

from __future__ import annotations

import json
from typing import Any, Optional

from fastapi import APIRouter, Depends, Header, HTTPException, status

from ...core.auth import require_internal_token
from ...core.llm_request_config import (
    HEADER_API_KEY,
    HEADER_BASE_URL,
    HEADER_MAX_TOKENS,
    HEADER_MODEL,
    HEADER_PROVIDER,
    HEADER_TEMPERATURE,
    config_from_headers,
    default_config,
)
from ...services.repository_generator import RepositoryDocumentationGenerator
from .schemas import (
    GenerateRepositoryDocumentationRequest,
    GenerateRepositoryDocumentationResponse,
    GroundingSource,
)

router = APIRouter()


def _build_llm(headers: dict[str, str]):
    """Build an LLM client from the workspace headers the backend sent."""

    config = config_from_headers(headers, fallback=default_config())
    try:
        return config.to_client()
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=f"Invalid AI provider configuration: {exc}",
        ) from exc


@router.post(
    "",
    response_model=GenerateRepositoryDocumentationResponse,
    dependencies=[Depends(require_internal_token)],
)
def generate_repository(
    req: GenerateRepositoryDocumentationRequest,
    x_ai_provider: Optional[str] = Header(default=None, alias=HEADER_PROVIDER),
    x_ai_model: Optional[str] = Header(default=None, alias=HEADER_MODEL),
    x_ai_base_url: Optional[str] = Header(default=None, alias=HEADER_BASE_URL),
    x_ai_api_key: Optional[str] = Header(default=None, alias=HEADER_API_KEY),
    x_ai_temperature: Optional[str] = Header(default=None, alias=HEADER_TEMPERATURE),
    x_ai_max_tokens: Optional[str] = Header(default=None, alias=HEADER_MAX_TOKENS),
) -> GenerateRepositoryDocumentationResponse:
    headers = {
        HEADER_PROVIDER: x_ai_provider or "",
        HEADER_MODEL: x_ai_model or "",
        HEADER_BASE_URL: x_ai_base_url or "",
        HEADER_API_KEY: x_ai_api_key or "",
        HEADER_TEMPERATURE: x_ai_temperature or "",
        HEADER_MAX_TOKENS: x_ai_max_tokens or "",
    }
    llm = _build_llm(headers)

    template_schema = _parse_template_schema(req.template_schema)

    snapshot = req.repository.model_dump()
    generator = RepositoryDocumentationGenerator(llm=llm)
    try:
        result = generator.generate(
            repository=snapshot,
            template=req.template,
            template_schema=template_schema,
            focus=req.focus,
        )
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=f"LLM provider '{llm.provider_name}' failed: {exc}",
        ) from exc

    return GenerateRepositoryDocumentationResponse(
        markdown=result.markdown,
        confidence=result.confidence,
        model=result.model,
        sources=[GroundingSource(**s) for s in result.sources],
        notes=result.notes,
        prompt_tokens=result.prompt_tokens,
        completion_tokens=result.completion_tokens,
    )


def _parse_template_schema(raw: Optional[str]) -> Optional[dict[str, Any]]:
    """Decode the template schema JSON sent by the backend."""

    if not raw:
        return None
    try:
        parsed = json.loads(raw)
    except (TypeError, ValueError) as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Invalid template_schema: {exc}",
        ) from exc
    if not isinstance(parsed, dict):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="template_schema must be a JSON object",
        )
    return parsed
