"""POST /drift — detect documentation drift between two source versions.

Combines two detectors:

* the **AST detector** (:mod:`app.services.drift`) which mechanically
  compares the parsed symbol tables of the "before" and "after" trees;
* the **semantic analyzer** (:mod:`app.services.semantic_drift`) which
  asks the LLM to find behavioural drift in the unified diff.

The semantic pass is optional: when the service runs with the mock
provider (or the LLM is unavailable) the endpoint still returns the
AST findings, so the feature degrades gracefully.
"""

from __future__ import annotations

from typing import Optional

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
from ...services.diffing import unified_diff
from ...services.drift import DriftDetector
from ...services.semantic_drift import SemanticDriftAnalyzer, SemanticDriftResult
from .schemas import (
    DriftDetectRequest,
    DriftDetectResponse,
    DriftFindingPayload,
)

router = APIRouter()


def get_llm(headers: dict[str, str] | None = None):
    config = config_from_headers(headers or {}, fallback=default_config())
    return config.to_client()


def _build_llm(headers: dict[str, str]):
    """Build the LLM client, turning a bad configuration into a 502."""

    try:
        return get_llm(headers)
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=f"Invalid AI provider configuration: {exc}",
        ) from exc


@router.post("", response_model=DriftDetectResponse,
             dependencies=[Depends(require_internal_token)])
def detect_drift(
    req: DriftDetectRequest,
    x_ai_provider: Optional[str] = Header(default=None, alias=HEADER_PROVIDER),
    x_ai_model: Optional[str] = Header(default=None, alias=HEADER_MODEL),
    x_ai_base_url: Optional[str] = Header(default=None, alias=HEADER_BASE_URL),
    x_ai_api_key: Optional[str] = Header(default=None, alias=HEADER_API_KEY),
    x_ai_temperature: Optional[str] = Header(default=None, alias=HEADER_TEMPERATURE),
    x_ai_max_tokens: Optional[str] = Header(default=None, alias=HEADER_MAX_TOKENS),
) -> DriftDetectResponse:
    files_before = [(f.path, f.content) for f in req.files_before]
    files_after = [(f.path, f.content) for f in req.files_after]

    # ---- 1) AST / heuristic detection --------------------------------
    detector = DriftDetector()
    report = detector.detect(
        files_before=files_before,
        files_after=files_after,
        document_markdown=req.document_markdown,
    )

    findings = [
        DriftFindingPayload(
            drift_kind=f.drift_kind,
            severity=f.severity,
            title=f.title,
            description=f.description,
            confidence=f.confidence,
            evidence=f.evidence,
            suggestion=f.suggestion,
        )
        for f in report.findings
    ]

    prompt_tokens = 0
    completion_tokens = 0

    # ---- 2) LLM-assisted semantic detection ---------------------------
    headers = {
        HEADER_PROVIDER: x_ai_provider or "",
        HEADER_MODEL: x_ai_model or "",
        HEADER_BASE_URL: x_ai_base_url or "",
        HEADER_API_KEY: x_ai_api_key or "",
        HEADER_TEMPERATURE: x_ai_temperature or "",
        HEADER_MAX_TOKENS: x_ai_max_tokens or "",
    }
    llm = _build_llm(headers)

    if llm.is_live():
        diff_text = unified_diff(files_before, files_after)
        analyzer = SemanticDriftAnalyzer(llm)
        try:
            semantic = analyzer.analyze(
                diff_text=diff_text,
                document_markdown=req.document_markdown,
                document_title=req.document_title or "",
            )
        except Exception as exc:
            # The AST findings are still valuable — a failed semantic
            # pass must not discard them.
            semantic = SemanticDriftResult(error=f"semantic pass failed: {exc}")
        prompt_tokens = semantic.prompt_tokens
        completion_tokens = semantic.completion_tokens
        for f in semantic.findings:
            findings.append(DriftFindingPayload(
                drift_kind=f.drift_kind,
                severity=f.severity,
                title=f.title,
                description=f.description,
                confidence=f.confidence,
                evidence=f.evidence,
                suggestion=f.suggestion,
            ))

    return DriftDetectResponse(
        findings=findings,
        markdown_summary=report.markdown_summary,
        prompt_tokens=prompt_tokens,
        completion_tokens=completion_tokens,
    )
