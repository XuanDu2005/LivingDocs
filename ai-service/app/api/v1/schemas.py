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
    template_schema: Optional[str] = Field(
        default=None,
        description="Canonical template JSON (sections + placeholders) as a string. "
                    "When present the prompt is built from the user's own template.",
    )
    focus: Optional[str] = Field(default=None, description="Optional focus area")
    language_hint: Optional[str] = Field(default=None, description="Override the auto-detected language")


class GroundingSource(BaseModel):
    qualified_name: str
    kind: str
    file: str
    lines: List[int]
    signature: str = ""


class RepositoryKeyFile(BaseModel):
    """A source file that is part of the repository snapshot."""

    path: str = Field(description="Repository-relative path of the file")
    content: str = Field(default="", description="Raw file contents")
    language: str = Field(default="", description="Detected language, or empty")


class RepositorySnapshotModel(BaseModel):
    """A structured picture of a whole repository.

    The repository-level generator works on this instead of a flat list
    of source files: an overview document needs the *layout* of the
    project (tree, directories, language mix), its dependency manifests
    and its entry points — none of which the AST analysers can see.
    """

    full_name: str = Field(default="", description="owner/name of the repository")
    description: Optional[str] = Field(default=None)
    default_branch: str = Field(default="main")
    tree: List[str] = Field(default_factory=list,
                            description="Every file path found in the repository")
    languages: dict = Field(default_factory=dict,
                            description="File count per language")
    directories: dict = Field(default_factory=dict,
                              description="File count per top-level directory")
    manifests: dict = Field(default_factory=dict,
                             description="Content of the dependency manifests, keyed by path")
    entry_points: List[str] = Field(default_factory=list)
    key_files: List[RepositoryKeyFile] = Field(default_factory=list)
    total_files: int = 0
    truncated: bool = False


class GenerateRepositoryDocumentationRequest(BaseModel):
    repository: RepositorySnapshotModel
    template: Optional[str] = Field(default=None,
                                    description="README | ARCHITECTURE | ONBOARDING_GUIDE | API_REFERENCE")
    template_schema: Optional[str] = Field(
        default=None,
        description="Canonical template JSON (sections + placeholders) as a string.",
    )
    focus: Optional[str] = Field(default=None, description="Optional focus area")


class GenerateRepositoryDocumentationResponse(BaseModel):
    markdown: str
    confidence: float
    model: str
    sources: List[GroundingSource] = Field(default_factory=list)
    notes: List[str] = Field(default_factory=list)
    prompt_tokens: int = 0
    completion_tokens: int = 0


class GenerateDocumentationResponse(BaseModel):
    markdown: str
    confidence: float
    model: str
    sources: List[GroundingSource] = Field(default_factory=list)
    notes: List[str] = Field(default_factory=list)
    # Token counts reported by the provider. The backend rolls these into
    # the workspace's AI usage report (ai_usage_stats).
    prompt_tokens: int = 0
    completion_tokens: int = 0


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
    # Token counts for the semantic (LLM) pass. Zero when the service
    # runs with the offline mock provider.
    prompt_tokens: int = 0
    completion_tokens: int = 0


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