"""LLM-assisted semantic drift detection.

The AST-based detector in :mod:`app.services.drift` catches mechanical
drift: a referenced symbol disappeared, a signature changed. It cannot
catch <em>semantic</em> drift — the code still exports the same names
but their behaviour changed, the doc describes a parameter that is no
longer honoured, or a whole feature was removed without the names
changing.

This module asks the LLM to compare the documentation against the
*diff* of the code and report semantic findings. The AST findings are
still produced; the LLM findings are merged in on top.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from typing import Iterable, List, Optional

from ..llm import LLMClient

#: The model must answer with a JSON array. We ask for a fenced block so
#: we can also accept providers that ignore the "no prose" instruction.
_JSON_ARRAY_RE = re.compile(r"\[.*\]", re.DOTALL)

_SYSTEM_PROMPT = (
    "You are LivingDocs' drift analyst. You compare a documentation page "
    "against a source-code diff and report places where the documentation "
    "no longer describes the code accurately.\n\n"
    "Report ONLY problems that are visible in the diff. Do not report "
    "style issues, missing examples, or anything you cannot point at in "
    "the diff.\n\n"
    "Answer with a JSON array and nothing else. Each element must be an "
    "object with these keys:\n"
    '  "drift_kind": one of "REFERENTIAL", "SIGNATURE", "SEMANTIC"\n'
    '  "severity": one of "LOW", "MEDIUM", "HIGH", "CRITICAL"\n'
    '  "title": short one-line summary\n'
    '  "description": what is wrong and why it matters\n'
    '  "confidence": number between 0 and 1\n'
    '  "evidence": object with any supporting details (e.g. the diff hunk)\n'
    '  "suggestion": the concrete documentation change to make\n'
)


@dataclass
class SemanticFinding:
    """One LLM-reported drift finding."""

    drift_kind: str
    severity: str
    title: str
    description: str
    confidence: float
    evidence: dict = field(default_factory=dict)
    suggestion: str = ""


@dataclass
class SemanticDriftResult:
    findings: List[SemanticFinding] = field(default_factory=list)
    prompt_tokens: int = 0
    completion_tokens: int = 0
    error: str = ""


class SemanticDriftAnalyzer:
    """Asks the LLM for semantic drift findings."""

    def __init__(self, llm: LLMClient, *, min_confidence: float = 0.5) -> None:
        self.llm = llm
        self.min_confidence = min_confidence

    def analyze(self, *,
                diff_text: str,
                document_markdown: str,
                document_title: str = "") -> SemanticDriftResult:
        if not diff_text.strip():
            return SemanticDriftResult(error="empty diff")

        user_prompt = _build_user_prompt(
            diff_text=diff_text,
            document_markdown=document_markdown,
            document_title=document_title,
        )

        try:
            response = self.llm.generate_documentation(
                system_prompt=_SYSTEM_PROMPT,
                user_prompt=user_prompt,
            )
        except Exception as exc:  # pragma: no cover - provider failure
            return SemanticDriftResult(error=f"LLM call failed: {exc}")

        findings = _parse_findings(response.text)
        kept = [f for f in findings if f.confidence >= self.min_confidence]
        return SemanticDriftResult(
            findings=kept,
            prompt_tokens=response.input_tokens,
            completion_tokens=response.output_tokens,
        )


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------


def _build_user_prompt(*, diff_text: str, document_markdown: str,
                       document_title: str) -> str:
    title = document_title or "(untitled document)"
    return (
        f"# Document under review\n\n"
        f"Title: {title}\n\n"
        f"```markdown\n{document_markdown}\n```\n\n"
        f"# Code diff (before → after)\n\n"
        f"```diff\n{diff_text}\n```\n\n"
        f"# Task\n"
        f"List every place where the documentation above is now inaccurate "
        f"or incomplete with respect to the diff. Return a JSON array."
    )


def _parse_findings(raw: str) -> List[SemanticFinding]:
    """Parse the model's answer into findings.

    Tolerates a fenced code block and trailing prose: we extract the
    outermost JSON array and ignore anything around it.
    """

    if not raw:
        return []

    candidates = []
    try:
        candidates.append(json.loads(raw))
    except (TypeError, ValueError):
        pass

    if not candidates:
        match = _JSON_ARRAY_RE.search(raw)
        if match:
            try:
                candidates.append(json.loads(match.group(0)))
            except (TypeError, ValueError):
                pass

    for candidate in candidates:
        if isinstance(candidate, list):
            return [_to_finding(item) for item in candidate if isinstance(item, dict)]
        if isinstance(candidate, dict) and isinstance(candidate.get("findings"), list):
            return [_to_finding(item) for item in candidate["findings"]
                    if isinstance(item, dict)]
    return []


def _to_finding(item: dict) -> SemanticFinding:
    try:
        confidence = float(item.get("confidence", 0.0))
    except (TypeError, ValueError):
        confidence = 0.0
    evidence = item.get("evidence")
    return SemanticFinding(
        drift_kind=str(item.get("drift_kind") or "SEMANTIC").upper(),
        severity=str(item.get("severity") or "MEDIUM").upper(),
        title=str(item.get("title") or "Documentation drift"),
        description=str(item.get("description") or ""),
        confidence=max(0.0, min(1.0, confidence)),
        evidence=evidence if isinstance(evidence, dict) else {},
        suggestion=str(item.get("suggestion") or ""),
    )
