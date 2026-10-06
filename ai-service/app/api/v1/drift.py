"""POST /drift — detect documentation drift between two source versions."""

from fastapi import APIRouter

from ...llm import LLMClient, client_from_env
from ...services import DriftDetector
from .schemas import (
    DriftDetectRequest,
    DriftDetectResponse,
    DriftFindingPayload,
)

router = APIRouter()

_llm: LLMClient | None = None


def get_llm() -> LLMClient:
    global _llm
    if _llm is None:
        _llm = client_from_env()
    return _llm


@router.post("", response_model=DriftDetectResponse)
def detect_drift(req: DriftDetectRequest) -> DriftDetectResponse:
    detector = DriftDetector()
    report = detector.detect(
        files_before=[(f.path, f.content) for f in req.files_before],
        files_after=[(f.path, f.content) for f in req.files_after],
        document_markdown=req.document_markdown,
    )
    return DriftDetectResponse(
        findings=[
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
        ],
        markdown_summary=report.markdown_summary,
    )