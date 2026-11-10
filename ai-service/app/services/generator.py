"""Documentation generator: builds Markdown documentation from parsed source."""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any, Iterable, List, Optional

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
    prompt_tokens: int = 0
    completion_tokens: int = 0


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
                 template_schema: Optional[dict[str, Any]] = None,
                 focus: Optional[str] = None,
                 language_hint: Optional[str] = None) -> GenerationResult:
        """Generate documentation for a set of (file_path, source) pairs.

        ``template_schema`` is the canonical template definition sent by
        the backend. When present it drives the document structure; the
        built-in presets are only a fallback for templates that have no
        schema of their own.
        """

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
        system_prompt = _system_prompt_for_template(template_name, template_schema)
        user_prompt = _user_prompt_for(parsed, focus, template_schema)

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
            prompt_tokens=response.input_tokens,
            completion_tokens=response.output_tokens,
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


_BASE_SYSTEM_PROMPT = (
    "You are LivingDocs, an AI documentation assistant. "
    "Your job is to produce accurate, grounded Markdown documentation for "
    "the source code provided. Every claim must be backed by a code entity "
    "found in the input. Never invent functions, parameters or behaviour."
)

_TEMPLATE_EXTRAS = {
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
    "ARCHITECTURE": "Describe the components, their responsibilities, the data flow "
                    "between them and the trust boundaries.",
}


def _normalise_template_key(template: str) -> str:
    """Normalise a template identifier into a preset key.

    The frontend sends a template *slug* (``default-api-reference``) while
    the backend uses a canonical doc type (``API_REFERENCE``). Both have to
    land on the same preset key, otherwise every workspace template
    silently fell back to ``MODULE_GUIDE``.
    """

    key = (template or "MODULE_GUIDE").upper().replace("-", "_").strip()
    if key.startswith("DEFAULT_"):
        key = key[len("DEFAULT_"):]
    return key or "MODULE_GUIDE"


def _system_prompt_for_template(template: str,
                                 template_schema: Optional[dict[str, Any]] = None) -> str:
    """Build the system prompt.

    A workspace's own template schema always wins over the built-in
    presets — the whole point of the template feature is that the
    user defines the document structure.
    """

    if template_schema:
        return _system_prompt_from_schema(template, template_schema)
    preset = _TEMPLATE_EXTRAS.get(
        _normalise_template_key(template), _TEMPLATE_EXTRAS["MODULE_GUIDE"])
    return _BASE_SYSTEM_PROMPT + "\n\n" + preset


def _system_prompt_from_schema(template: str,
                               schema: dict[str, Any]) -> str:
    """Render a canonical template schema into a system prompt.

    The canonical shape produced by the backend is::

        {
          "name": "API Reference",
          "instructions": "Formatting rules ...",
          "variables": [{"key": "moduleName", ...}],
          "sections": [
            {"id": "endpoints", "heading": "Endpoints", "level": 2,
             "placeholders": [
               {"key": "endpoints", "prompt": "List every public endpoint…",
                "required": true, "maxWords": 80, "binding": "code.route"}
             ]}
          ]
        }

    The prompt of a placeholder lives on the *placeholder* — not on the
    section — so that is where the guidance is read from. Reading it from
    the section level (as an earlier revision did) silently produced an
    empty guidance and the wrong ``required`` flag, which is exactly the
    "template is passed but ignored" bug this renderer exists to fix.

    Unknown keys are ignored so a newer backend can add fields without
    breaking this service.
    """

    name = str(schema.get("name") or schema.get("titleHint") or template or "DOCUMENT")
    sections = schema.get("sections") or []
    instructions = str(schema.get("instructions") or "").strip()
    variables = schema.get("variables") or []

    parts = [
        _BASE_SYSTEM_PROMPT,
        "",
        f"Follow the '{name}' documentation template exactly.",
        "Emit one Markdown section per entry below, in the given order, "
        "using the heading text verbatim.",
    ]
    if instructions:
        parts += ["", f"Template instructions (formatting rules): {instructions}"]

    variable_keys = [str(v.get("key")) for v in variables
                     if isinstance(v, dict) and v.get("key")]
    if variable_keys:
        parts += ["", "Template variables to substitute in the title: "
                  + ", ".join(variable_keys)]

    if sections:
        parts += ["", "## Required sections"]
        for idx, section in enumerate(sections, start=1):
            if not isinstance(section, dict):
                continue
            heading = str(section.get("heading") or section.get("title") or f"Section {idx}")
            level = int(section.get("level") or 2)
            parts.append(f"{idx}. {'#' * max(1, min(6, level))} {heading}")
            # The prompt of a placeholder lives on the placeholder itself.
            placeholders = section.get("placeholders") or []
            for ph in placeholders:
                if not isinstance(ph, dict):
                    continue
                key = str(ph.get("key") or "")
                prompt = str(ph.get("prompt") or "").strip()
                required = bool(ph.get("required", False))
                max_words = ph.get("maxWords")
                binding = ph.get("binding")
                bits = []
                if required:
                    bits.append("REQUIRED")
                if max_words:
                    bits.append(f"max {max_words} words")
                if binding:
                    bits.append(f"ground it in the code entity of type '{binding}'")
                suffix = f" ({', '.join(bits)})" if bits else ""
                parts.append(f"   - {key}{suffix}")
                if prompt:
                    parts.append(f"     Guidance: {prompt}")
    return "\n".join(parts)


def _user_prompt_for(parsed: Iterable[ParsedFile],
                     focus: Optional[str],
                     template_schema: Optional[dict[str, Any]] = None) -> str:
    parts = ["# Parsed source code\n"]
    for file in parsed:
        parts.append(f"## {file.file_path} ({file.language})")
        if file.module_docstring:
            parts.append(f"Module docstring:\n```\n{file.module_docstring}\n```\n")
        for e in file.entities:
            parts.append(_entity_to_prompt(e, file.source))
    if template_schema:
        parts.append("\n# Required output structure\n")
        parts.append(json.dumps(template_schema, indent=2))
    if focus:
        parts.append(f"\n# Focus\n{focus}")
    parts.append("\n# Output\nProduce the final Markdown documentation now.")
    return "\n\n".join(parts)


def _entity_to_prompt(e: CodeEntity, source: str = "") -> str:
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
    # The body of the entity — not just its signature — is what lets the
    # model describe real behaviour instead of guessing from the name.
    body = _entity_body(e, source)
    body_block = f"\nImplementation:\n```\n{body}\n```\n" if body else ""
    return (f"### {e.kind.value} `{e.qualified_name}`\n"
            f"Signature:\n```\n{sig}\n```\n"
            f"{body_block}"
            f"{docstring}\n"
            f"Metadata:\n```json\n{metadata}\n```\n")


def _entity_body(e: CodeEntity, source: str) -> str:
    """Slice the entity's implementation out of the original source.

    Falls back to an empty string when the source is unavailable or the
    recorded line range does not fit it.
    """

    if not source:
        return ""
    lines = source.splitlines()
    start = max(0, e.start_line - 1)
    end = min(len(lines), e.end_line)
    if start >= end:
        return ""
    return "\n".join(lines[start:end]).strip()


def _fallback_markdown(parsed: Iterable[ParsedFile], template_name: str) -> str:
    parts = [f"# {template_name.title()}"]
    for file in parsed:
        parts.append(f"## {file.file_path}")
        for e in file.entities:
            parts.append(f"- **{e.kind.value}** `{e.qualified_name}` — {e.signature}")
    return "\n\n".join(parts)
