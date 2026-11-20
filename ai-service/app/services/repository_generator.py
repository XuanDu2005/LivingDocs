"""Repository-level documentation generation.

The regular generator answers "describe this module". This one answers
"describe this repository" — the question a new reader actually has
when they open a project for the first time.

The difference is not only the prompt. The AST analysers of the regular
generator only see code files; a README or an architecture guide cannot
be written from parsed classes alone, because it needs the layout of the
project, its dependency manifests and its entry points. The
repository-level generator therefore works on a *repository snapshot*:
a structured description of the project (tree, language mix, top-level
directories, manifests, entry points, a sample of the source files)
rather than on a flat list of parsable files.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any, List, Optional

from ..llm import LLMClient


@dataclass
class RepositoryGenerationResult:
    """The output of a repository-level documentation request."""

    markdown: str
    confidence: float
    model: str
    sources: List[dict] = field(default_factory=list)
    notes: List[str] = field(default_factory=list)
    prompt_tokens: int = 0
    completion_tokens: int = 0


class RepositoryDocumentationGenerator:
    """Generates overview documentation for a whole repository."""

    def __init__(self, llm: Optional[LLMClient] = None) -> None:
        self.llm = llm or LLMClient()

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def generate(self, *,
                 repository: dict[str, Any],
                 template: Optional[str] = None,
                 template_schema: Optional[dict[str, Any]] = None,
                 focus: Optional[str] = None) -> RepositoryGenerationResult:
        """Generate one overview document for the repository."""

        template_name = template or "README"
        notes: List[str] = []

        full_name = str(repository.get("full_name") or "the repository")
        tree = list(repository.get("tree") or [])
        key_files = list(repository.get("key_files") or [])
        manifests = dict(repository.get("manifests") or {})

        if not tree and not key_files:
            notes.append(
                "The repository snapshot is empty — the generated document "
                "will be based on the repository description alone."
            )

        system_prompt = _system_prompt_for_template(template_name, template_schema)
        user_prompt = _user_prompt_for(repository, template_name, focus)

        response = self.llm.generate_documentation(
            system_prompt=system_prompt,
            user_prompt=user_prompt,
        )
        markdown = (response.text or "").strip()
        if not markdown:
            markdown = _fallback_markdown(repository, template_name)
            notes.append("The provider returned no content — a structured "
                         "fallback was used instead.")

        confidence = _confidence(repository, response.text)

        return RepositoryGenerationResult(
            markdown=markdown,
            confidence=confidence,
            model=response.model,
            sources=_sources_for(repository, manifests, key_files),
            notes=notes,
            prompt_tokens=response.input_tokens,
            completion_tokens=response.output_tokens,
        )


# ---------------------------------------------------------------------------
# Prompt construction
# ---------------------------------------------------------------------------

_BASE_SYSTEM_PROMPT = (
    "You are LivingDocs, an AI documentation assistant. "
    "Your job is to produce accurate, grounded documentation for a "
    "whole software repository. Every claim must be backed by the "
    "repository snapshot you are given: its file tree, its dependency "
    "manifests and its source files. Never invent components, "
    "dependencies, endpoints or commands that are not in the snapshot."
)

_TEMPLATE_EXTRAS = {
    "README": "Produce a top-level README with sections: Overview, "
              "Installation, Usage, Configuration, Project Structure, Contributing.",
    "ARCHITECTURE": "Describe the components, their responsibilities, the data flow "
                    "between them and the trust boundaries. Name concrete "
                    "directories and files.",
    "ONBOARDING_GUIDE": "Write a guide for new engineers: what the system does, how to "
                        "run it, where things live, common workflows.",
    "API_REFERENCE": "Use a strict API reference layout: one section per endpoint or "
                     "public interface, with its signature, parameters, return value "
                     "and an example.",
    "MODULE_GUIDE": "Start with a one-paragraph summary, then describe the main "
                    "modules and how they fit together.",
}


def _normalise_template_key(template: Optional[str]) -> str:
    key = (template or "README").upper().replace("-", "_").strip()
    if key.startswith("DEFAULT_"):
        key = key[len("DEFAULT_"):]
    return key or "README"


def _system_prompt_for_template(template: Optional[str],
                                 template_schema: Optional[dict[str, Any]] = None) -> str:
    """Build the system prompt.

    A workspace's own template schema always wins over the built-in
    presets — the whole point of the template feature is that the user
    defines the document structure.
    """

    if template_schema:
        return _system_prompt_from_schema(template, template_schema)
    preset = _TEMPLATE_EXTRAS.get(
        _normalise_template_key(template), _TEMPLATE_EXTRAS["MODULE_GUIDE"])
    return _BASE_SYSTEM_PROMPT + "\n\n" + preset


def _system_prompt_from_schema(template: Optional[str],
                                schema: dict[str, Any]) -> str:
    """Render a canonical template schema into a system prompt.

    The canonical shape produced by the backend is the same one the
    per-module generator understands — sections with headings and
    placeholders, each placeholder carrying its own guidance.
    """

    from .generator import _system_prompt_from_schema as _from_schema

    return _from_schema(template or "REPOSITORY", schema, base_prompt=_BASE_SYSTEM_PROMPT)


def _user_prompt_for(repository: dict[str, Any],
                     template_name: str,
                     focus: Optional[str]) -> str:
    """Render the repository snapshot into the user prompt."""

    parts: List[str] = ["# Repository snapshot\n"]

    full_name = str(repository.get("full_name") or "(unknown)")
    parts.append(f"Repository: `{full_name}`")
    branch = repository.get("default_branch")
    if branch:
        parts.append(f"Default branch: `{branch}`")
    description = repository.get("description")
    if description:
        parts.append(f"Description: {description}")
    total = repository.get("total_files")
    if total:
        parts.append(f"Total files: {total}")
    if repository.get("truncated"):
        parts.append(
            "Note: the file tree below is truncated — the repository is larger "
            "than the scan limit, so some directories are missing."
        )

    languages = repository.get("languages") or {}
    if languages:
        parts.append("\n## Language breakdown")
        for language, count in sorted(languages.items(), key=lambda kv: -kv[1]):
            parts.append(f"- {language}: {count} file(s)")

    directories = repository.get("directories") or {}
    if directories:
        parts.append("\n## Top-level directories")
        for directory, count in sorted(directories.items(), key=lambda kv: -kv[1]):
            parts.append(f"- `{directory}/` — {count} file(s)")

    entry_points = repository.get("entry_points") or []
    if entry_points:
        parts.append("\n## Entry points")
        for path in entry_points:
            parts.append(f"- `{path}`")

    manifests = repository.get("manifests") or {}
    if manifests:
        parts.append("\n## Dependency manifests and configuration")
        for path, content in manifests.items():
            parts.append(f"### `{path}`\n```\n{content}\n```")

    tree = repository.get("tree") or []
    if tree:
        parts.append("\n## File tree")
        for path in tree:
            parts.append(f"- `{path}`")

    key_files = repository.get("key_files") or []
    if key_files:
        parts.append("\n## Key source files")
        for entry in key_files:
            path = str(entry.get("path") or "")
            content = str(entry.get("content") or "")
            language = str(entry.get("language") or "")
            parts.append(f"### `{path}`" + (f" ({language})" if language else ""))
            if content:
                parts.append(f"```\n{content}\n```")

    parts.append(f"\n# Output\nProduce the '{template_name}' document for the "
                 "whole repository now.")
    if focus:
        parts.append(f"\n# Focus\n{focus}")
    return "\n\n".join(parts)


def _sources_for(repository: dict[str, Any],
                 manifests: dict[str, str],
                 key_files: list[dict[str, Any]]) -> List[dict]:
    """Build the grounding evidence of the generated document.

    The grounding records the files the document is about, so a reader
    can check the claims. The repository itself is the "entity" here —
    the sources point at the files that make it up.
    """

    sources: List[dict] = []
    full_name = str(repository.get("full_name") or "repository")

    for path in list(manifests.keys()):
        sources.append({
            "qualified_name": f"{full_name}:{path}",
            "kind": "manifest",
            "file": path,
            "lines": [1],
            "signature": "",
        })

    for entry in key_files:
        path = str(entry.get("path") or "")
        sources.append({
            "qualified_name": f"{full_name}:{path}",
            "kind": "file",
            "file": path,
            "lines": [1],
            "signature": "",
        })

    return sources


def _confidence(repository: dict[str, Any], response_text: Optional[str]) -> float:
    """A heuristic confidence for a repository-level document.

    A repository snapshot with a full tree and a few manifests is solid
    grounding; an empty one is not. The score grows with the amount of
    evidence and with the length of the answer.
    """

    tree = repository.get("tree") or []
    manifests = repository.get("manifests") or {}
    key_files = repository.get("key_files") or []

    base = 0.35
    base += min(0.25, len(tree) / 400.0)
    base += min(0.15, len(manifests) * 0.03)
    base += min(0.15, len(key_files) * 0.01)
    if response_text and len(response_text.split()) > 80:
        base += 0.1
    return round(min(0.95, base), 3)


def _fallback_markdown(repository: dict[str, Any], template_name: str) -> str:
    """A structured fallback used when the provider returns nothing.

    It is deliberately honest: it lists what was found instead of
    inventing prose, so a reviewer can tell the difference between "the
    AI could not answer" and "the AI answered".
    """

    full_name = str(repository.get("full_name") or "repository")
    parts = [f"# {template_name.title()} — {full_name}"]
    description = repository.get("description")
    if description:
        parts.append(f"\n{description}")
    tree = repository.get("tree") or []
    if tree:
        parts.append("\n## Project structure\n")
        parts.extend(f"- `{path}`" for path in tree[:200])
    parts.append("\n## Note\n")
    parts.append("This document was assembled from the repository snapshot "
                 "without a language model. Review it against the source "
                 "before publishing.")
    return "\n\n".join(parts)


def _dump(value: Any) -> str:  # pragma: no cover - debugging helper
    return json.dumps(value, indent=2, default=str)
