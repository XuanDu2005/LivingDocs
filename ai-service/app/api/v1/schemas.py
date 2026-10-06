"""Pydantic request / response models for the AI HTTP API."""

from __future__ import annotations

from typing import List, Optional

from pydantic import BaseModel, Field


class SourceFile(BaseModel):
    path: str = Field(description="Path of the source file (used to pick the analyser)")
    content: str = Field(description="Raw file contents")


class GenerateDocumentationRequest(BaseModel):
    files: List[SourceFile]
    template: Optional[str] = Field(default=None, description="Module guide / API / README / ADR / ...")
    focus: Optional[str] = Field(default=None, description="Optional focus area")
    language_hint: Optional[str] = Field(default=None, description="Override the auto-detected language")


class GroundingSource(BaseModel):
    qualified_name: str
    kind: str
    file: str
    lines: List[int]
    signature: str = ""


class GenerateDocumentationResponse(BaseModel):
    markdown: str
    confidence: float
    model: str
    sources: List[GroundingSource] = Field(default_factory=list)
    notes: List[str] = Field(default_factory=list)


class DriftDetectRequest(BaseModel):
    files_before: List[SourceFile] = Field(default_factory=list)
    files_after: List[SourceFile] = Field(default_factory=list)
    document_markdown: str
    document_title: Optional[str] = None


class DriftFindingPayload(BaseModel):
    drift_kind: str
    severity: str
    title: str
    description: str
    confidence: float
    evidence: dict = Field(default_factory=dict)
    suggestion: str = ""


class DriftDetectResponse(BaseModel):
    findings: List[DriftFindingPayload]
    markdown_summary: str


class IndexDocumentRequest(BaseModel):
    document_id: str
    title: str
    body: str
    doc_type: str = "DOCUMENT"
    workspace_id: Optional[str] = None
    metadata: Optional[dict] = None


class IndexDocumentResponse(BaseModel):
    chunks_indexed: int


class KnowledgeSearchRequest(BaseModel):
    query: str
    workspace_id: Optional[str] = None
    top_k: int = 5


class KnowledgeHit(BaseModel):
    doc_id: str
    text: str
    score: float
    metadata: dict


class KnowledgeSearchResponse(BaseModel):
    hits: List[KnowledgeHit]


class ParseRequest(BaseModel):
    path: str
    content: str


class ParseResponse(BaseModel):
    language: str
    entities: list
    imports: List[str] = Field(default_factory=list)
    module_docstring: Optional[str] = None