"""Documentation generator: builds Markdown documentation from parsed source."""

from __future__ import annotations

import json
import time
from dataclasses import dataclass, field
from typing import Iterable, List, Optional

from ..ast import CodeEntity, CodeEntityKind, ParsedFile, analyser_for
from ..ast.models import CodeEntityKind as Kind
from ..llm import LLMClient


@dataclass
class GenerationResult:
    """The output of a documentation generation request."""

    markdown: str
    confidence: float
    model: str
    sources: List[dict] = field(default_factory=list)
    notes: List[str] = field(default_factory=list)


class DocumentationGenerator:
    """High-level facade that ties together AST extraction + LLM drafting."""

    def __init__(self, llm: Optional[LLMClient] = None, *,
                 default_template: str = "MODULE_GUIDE") -> None:
        self.llm = llm or LLMClient()
        self.default_template = default_template

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def generate(self, *, files: Iterable[tuple[str, str]],
                 template: Optional[str] = None,
                 focus: Optional[str] = None,
                 language_hint: Optional[str] = None) -> GenerationResult:
        """Generate documentation for a set of (file_path, source) pairs."""

        template_name = template or self.default_template
        parsed: List[ParsedFile] = []
        notes: List[str] = []

        for path, source in files:
            language = language_hint or _language_from_extension(path)
            analyser = analyser_for(language)
            if analyser is None:
                notes.append(f"No analyser for {path} (language={language}); skipped")
                continue
            parsed.append(analyser(path, source))

        if not parsed:
            markdown = self._empty_response(template_name)
            return GenerationResult(markdown=markdown, confidence=0.0,
                                    model=self.llm.model, notes=notes)

        entity_summary = _summarise_entities(parsed)
        system_prompt = _system_prompt_for_template(template_name)
        user_prompt = _user_prompt_for(parsed, focus)

        response = self.llm.generate_documentation(
            system_prompt=system_prompt,
            user_prompt=user_prompt,
        )
        markdown = response.text.strip() or _fallback_markdown(parsed, template_name)
        confidence = _heuristic_confidence(parsed, response.text)

        return GenerationResult(
            markdown=markdown,
            confidence=confidence,
            model=response.model,
            sources=entity_summary,
            notes=notes,
        )

    # ------------------------------------------------------------------
    # Templates
    # ------------------------------------------------------------------

    def _empty_response(self, template_name: str) -> str:
        return (f"# {template_name.title()} (no source provided)\n\n"
                "_The generator received no parsable source files._\n")


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------


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


def _summarise_entities(parsed: Iterable[ParsedFile]) -> List[dict]:
    summary: List[dict] = []
    for file in parsed:
        for e in file.entities:
            summary.append({
                "qualified_name": e.qualified_name,
                "kind": e.kind.value,
                "file": e.file_path,
                "lines": [e.start_line, e.end_line],
                "signature": e.signature,
            })
    return summary


def _heuristic_confidence(parsed: Iterable[ParsedFile], response_text: str) -> float:
    entities = sum(len(p.entities) for p in parsed)
    if entities == 0:
        return 0.0
    base = min(0.95, 0.4 + 0.05 * entities)
    if response_text and len(response_text.split()) > 50:
        base = min(0.95, base + 0.1)
    return round(base, 3)


def _system_prompt_for_template(template: str) -> str:
    template = (template or "MODULE_GUIDE").upper()
    base = (
        "You are LivingDocs, an AI documentation assistant. "
        "Your job is to produce accurate, grounded Markdown documentation for "
        "the source code provided. Every claim must be backed by a code entity "
        "found in the input. Never invent functions, parameters or behaviour."
    )
    extras = {
        "API_REFERENCE": "Use a strict API reference layout: method signature, "
                          "parameters table, return value, exceptions, example.",
        "MODULE_GUIDE": "Start with a one-paragraph summary, then describe each public "
                         "class / function in its own subsection.",
        "README": "Produce a top-level README with sections: Overview, Installation, "
                        "Usage, Configuration, Contributing.",
        "ADR": "Produce an Architecture Decision Record with sections: Context, Decision, "
                  "Consequences, Alternatives.",
        "ONBOARDING_GUIDE": "Write a guide for new engineers: architecture, how to run, "
                                "where things are, common workflows.",
    }
    return base + "\n\n" + extras.get(template, extras["MODULE_GUIDE"])


def _user_prompt_for(parsed: Iterable[ParsedFile], focus: Optional[str]) -> str:
    parts = ["# Parsed source code\n"]
    for file in parsed:
        parts.append(f"## {file.file_path} ({file.language})")
        if file.module_docstring:
            parts.append(f"Module docstring:\n```\n{file.module_docstring}\n```\n")
        for e in file.entities:
            parts.append(_entity_to_prompt(e))
    if focus:
        parts.append(f"\n# Focus\n{focus}")
    parts.append("\n# Output\nProduce the final Markdown documentation now.")
    return "\n\n".join(parts)


def _entity_to_prompt(e: CodeEntity) -> str:
    sig = sig_block_lines = "\n".join(f"  {ln}" for ln in (e.signature or "").splitlines())
    docstring = f"\nDocstring:\n```\n{e.docstring}\n```" if e.docstring else ""
    params = ", ".join(f"{p.name}: {p.type_hint or '?'}" for p in e.parameters) if e.parameters else ""
    metadata = json.dumps({
        "decorators": e.decorators,
        "modifiers": e.modifiers,
        "return_type": e.return_type,
        "parameters": [{"name": p.name, "type_hint": p.type_hint} for p in e.parameters],
        "parent": e.parent_qualified_name,
        "lines": [e.start_line, e.end_line],
    }, indent=2)
    return (f"### {e.kind.value} `{e.qualified_name}`\n"
            f"Signature:\n```\n{sig}\n```\n"
            f"{docstring}\n"
            f"Metadata:\n```json\n{metadata}\n```\n")


def _fallback_markdown(parsed: Iterable[ParsedFile], template_name: str) -> str:
    parts = [f"# {template_name.title()}"]
    for file in parsed:
        parts.append(f"## {file.file_path}")
        for e in file.entities:
            parts.append(f"- **{e.kind.value}** `{e.qualified_name}` — {e.signature}")
    return "\n\n".join(parts)