"""Drift detector: compares documentation against code changes and reports drift."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from typing import Iterable, List, Optional

from ..ast import CodeEntity, CodeEntityKind, ParsedFile, analyser_for


@dataclass
class DriftFinding:
    """One concrete piece of documentation drift."""

    drift_kind: str  # REFERENTIAL | SIGNATURE | SEMANTIC
    severity: str    # LOW | MEDIUM | HIGH | CRITICAL
    title: str
    description: str
    confidence: float
    evidence: dict = field(default_factory=dict)
    suggestion: str = ""


@dataclass
class DriftReport:
    findings: List[DriftFinding] = field(default_factory=list)
    markdown_summary: str = ""

    @property
    def has_findings(self) -> bool:
        return bool(self.findings)

    def to_dict(self) -> dict:
        return {
            "findings": [
                {
                    "drift_kind": f.drift_kind,
                    "severity": f.severity,
                    "title": f.title,
                    "description": f.description,
                    "confidence": f.confidence,
                    "evidence": f.evidence,
                    "suggestion": f.suggestion,
                }
                for f in self.findings
            ],
            "markdown_summary": self.markdown_summary,
        }


class DriftDetector:
    """Compares a documentation body against parsed source and reports drift.

    The detector itself is heuristic for the MVP: it builds a set of code
    entity names from the parsed source and looks for references to those
    names in the documentation. When a name disappears from source but is
    still referenced in the docs, the detector flags REFERENTIAL drift.
    A real implementation will also call out SIGNATURE / SEMANTIC drift.
    """

    REFERENCE_PATTERNS = [
        re.compile(r"`([A-Za-z_][\w.]*)`"),
        re.compile(r"\b([A-Z][\w]+)\b"),
    ]

    def detect(self, *,
               files_before: Iterable[tuple[str, str]],
               files_after: Iterable[tuple[str, str]],
               document_markdown: str,
               ai_suggested_markdown: Optional[str] = None) -> DriftReport:
        entities_before = _entities_by_name(_parse_all(files_before))
        entities_after = _entities_by_name(_parse_all(files_after))

        doc_references = _extract_doc_references(document_markdown)
        findings: List[DriftFinding] = []

        # REFERENTIAL drift — referenced entity was removed or renamed.
        removed = sorted(set(entities_before) - set(entities_after))
        for ref in sorted(doc_references):
            if ref in removed:
                findings.append(DriftFinding(
                    drift_kind="REFERENTIAL",
                    severity="HIGH",
                    title=f"Outdated reference to `{ref}`",
                    description=(f"The documentation references `{ref}`, but the entity "
                                 f"no longer exists in the source code."),
                    confidence=0.85,
                    evidence={"removed_entity": ref, "removed_from_source": True},
                    suggestion=(f"Update or remove every mention of `{ref}` from the "
                                f"documentation. If a successor exists, replace it with "
                                f"the new name."),
                ))

        # SIGNATURE drift — function signature changed.
        for name in sorted(set(entities_before) & set(entities_after)):
            before, after = entities_before[name], entities_after[name]
            if before.signature != after.signature:
                findings.append(DriftFinding(
                    drift_kind="SIGNATURE",
                    severity="MEDIUM",
                    title=f"Signature change in `{name}`",
                    description=(f"`{name}` was `{before.signature}` and is now "
                                 f"`{after.signature}`."),
                    confidence=0.75,
                    evidence={"before": before.signature, "after": after.signature,
                              "lines": [after.start_line, after.end_line]},
                    suggestion=f"Refresh the signature of `{name}` in the documentation.",
                ))

        # SEMANTIC drift — public surface changes (heuristic: removed public class).
        for removed_name in removed:
            ent = entities_before[removed_name]
            if ent.kind in (CodeEntityKind.CLASS, CodeEntityKind.FUNCTION):
                findings.append(DriftFinding(
                    drift_kind="SEMANTIC",
                    severity="HIGH",
                    title=f"Public API change: `{removed_name}` removed",
                    description=(f"The public {ent.kind} `{removed_name}` no longer exists."),
                    confidence=0.8,
                    evidence={"removed_entity": removed_name, "kind": ent.kind.value,
                              "previous_file": ent.file_path,
                              "previous_lines": [ent.start_line, ent.end_line]},
                    suggestion="Describe the new behaviour or add a deprecation note.",
                ))

        markdown = _render_markdown(findings)
        return DriftReport(findings=findings, markdown_summary=markdown)


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------


def _parse_all(files: Iterable[tuple[str, str]]) -> List[ParsedFile]:
    out: List[ParsedFile] = []
    for path, source in files:
        language = _language_from_extension(path)
        analyser = analyser_for(language)
        if analyser is None:
            continue
        out.append(analyser(path, source))
    return out


def _language_from_extension(path: str) -> str:
    if path.endswith(".py"):
        return "python"
    if path.endswith((".ts", ".tsx")):
        return "typescript"
    if path.endswith((".js", ".jsx", ".mjs", ".cjs")):
        return "javascript"
    if path.endswith(".java"):
        return "java"
    return ""


def _entities_by_name(parsed: Iterable[ParsedFile]) -> dict[str, CodeEntity]:
    by_name: dict[str, CodeEntity] = {}
    for file in parsed:
        for e in file.entities:
            by_name[e.simple_name] = e
    return by_name


def _extract_doc_references(markdown: str) -> set[str]:
    refs: set[str] = set()
    for pattern in DriftDetector.REFERENCE_PATTERNS:
        for match in pattern.finditer(markdown or ""):
            refs.add(match.group(1))
    return refs


def _render_markdown(findings: List[DriftFinding]) -> str:
    if not findings:
        return "No documentation drift detected."
    parts = [f"# Drift report — {len(findings)} finding(s)\n"]
    for f in findings:
        parts.append(f"## [{f.severity}] {f.title}")
        parts.append(f"- **Kind:** {f.drift_kind}")
        parts.append(f"- **Confidence:** {f.confidence:.2f}")
        parts.append(f"- **Description:** {f.description}")
        if f.suggestion:
            parts.append(f"- **Suggested fix:** {f.suggestion}")
    return "\n\n".join(parts)