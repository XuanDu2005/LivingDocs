"""POST /generate — generate documentation from parsed source."""

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
from ...services import DocumentationGenerator
from .schemas import (
    GenerateDocumentationRequest,
    GenerateDocumentationResponse,
    GroundingSource,
)

router = APIRouter()


def get_llm(headers: dict[str, str] | None = None):
    """Build an LLM client from the workspace headers the backend sent.

    Falls back to the container's environment configuration when the
    backend does not send any ``X-Ai-*`` headers (e.g. a developer
    poking the service directly with curl).
    """

    config = config_from_headers(headers or {}, fallback=default_config())
    return config.to_client()


def _build_llm(headers: dict[str, str]):
    """Build the LLM client, turning a bad configuration into a 502.

    A workspace that picked OpenAI without an API key, or a custom
    provider without a base URL, is a configuration error — not a bug
    in the service. Surfacing it as a clean 502 lets the backend show
    the user an actionable message.
    """

    try:
        return get_llm(headers)
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=f"Invalid AI provider configuration: {exc}",
        ) from exc


@router.post("", response_model=GenerateDocumentationResponse,
             dependencies=[Depends(require_internal_token)])
def generate(
    req: GenerateDocumentationRequest,
    x_ai_provider: Optional[str] = Header(default=None, alias=HEADER_PROVIDER),
    x_ai_model: Optional[str] = Header(default=None, alias=HEADER_MODEL),
    x_ai_base_url: Optional[str] = Header(default=None, alias=HEADER_BASE_URL),
    x_ai_api_key: Optional[str] = Header(default=None, alias=HEADER_API_KEY),
    x_ai_temperature: Optional[str] = Header(default=None, alias=HEADER_TEMPERATURE),
    x_ai_max_tokens: Optional[str] = Header(default=None, alias=HEADER_MAX_TOKENS),
) -> GenerateDocumentationResponse:
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

    generator = DocumentationGenerator(llm=llm)
    try:
        result = generator.generate(
            files=[(f.path, f.content) for f in req.files],
            template=req.template,
            template_schema=template_schema,
            focus=req.focus,
            language_hint=req.language_hint,
        )
    except Exception as exc:
        # A misconfigured or unreachable provider (bad key, wrong base
        # URL, provider down) must surface as a clean 502 with an
        # actionable message — not an unhandled 500 with a stack trace.
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=f"LLM provider '{llm.provider_name}' failed: {exc}",
        ) from exc

    return GenerateDocumentationResponse(
        markdown=result.markdown,
        confidence=result.confidence,
        model=result.model,
        sources=[GroundingSource(**s) for s in result.sources],
        notes=result.notes,
        prompt_tokens=result.prompt_tokens,
        completion_tokens=result.completion_tokens,
    )


def _parse_template_schema(raw: Optional[str]) -> Optional[dict[str, Any]]:
    """Decode the template schema JSON sent by the backend.

    A malformed schema must not fail the whole request — the generator
    simply falls back to its built-in preset for the template name.
    """

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
