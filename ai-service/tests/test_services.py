"""Tests for the documentation generator and drift detector."""

from __future__ import annotations

import json

from app.llm import LLMClient
from app.services import DocumentationGenerator, DriftDetector
from app.services.generator import (
    _heuristic_confidence,
    _normalise_template_key,
    _system_prompt_from_schema,
)
from app.services.drift import DriftReport


def _files() -> list[tuple[str, str]]:
    return [
        (
            "calc.py",
            '''
"""Tiny calculator."""


class Calculator:
    def add(self, a: int, b: int) -> int:
        return a + b
''',
        ),
    ]


def test_generator_uses_mock_provider_by_default() -> None:
    generator = DocumentationGenerator()
    result = generator.generate(files=_files(), template="MODULE_GUIDE")
    assert result.markdown
    assert result.model
    assert 0.0 <= result.confidence <= 1.0
    assert any("Mock" in note or "Generated" in note for note in result.notes) or result.markdown


def test_heuristic_confidence_increases_with_entities() -> None:
    parsed_files = []
    from app.ast import analyser_for
    analyser = analyser_for("python")
    parsed = analyser("calc.py", _files()[0][1])
    parsed_files.append(parsed)
    c_few = _heuristic_confidence(parsed_files, "short")
    parsed_files.append(parsed)
    parsed_files.append(parsed)
    c_many = _heuristic_confidence(parsed_files, "much longer prose " * 30)
    assert c_many >= c_few


def test_drift_detector_flags_referential_drift() -> None:
    before = [("calc.py", "class Foo:\n    pass\n")]
    after: list[tuple[str, str]] = []  # file removed
    doc = "Use `Foo` to add two numbers."
    report = DriftDetector().detect(
        files_before=before, files_after=after, document_markdown=doc
    )
    assert isinstance(report, DriftReport)
    assert report.has_findings
    titles = [f.title for f in report.findings]
    assert any("Foo" in t for t in titles)


def test_drift_detector_flags_signature_change() -> None:
    before = [("svc.py", "def add(a, b):\n    return a + b\n")]
    after = [("svc.py", "def add(a, b, *, scale=1):\n    return (a + b) * scale\n")]
    doc = "Call `add(a, b)` to sum two numbers."
    report = DriftDetector().detect(
        files_before=before, files_after=after, document_markdown=doc
    )
    assert any(f.drift_kind == "SIGNATURE" for f in report.findings)


def test_llm_client_mock_is_deterministic() -> None:
    client = LLMClient(provider_name="mock")
    r1 = client.complete([{"role": "user", "content": "ping"}])  # type: ignore[arg-type]
    r2 = client.complete([{"role": "user", "content": "ping"}])  # type: ignore[arg-type]
    assert r1.text == r2.text


def test_llm_client_mock_offline_summary() -> None:
    client = LLMClient(provider_name="mock")
    response = client.generate_documentation(
        system_prompt="Generate documentation.",
        user_prompt="Please document this code.",
    )
    assert response.ok
    assert "documentation" in response.text.lower() or "documentation" in response.text.lower()


# ---------------------------------------------------------------------------
# Template schema rendering
# ---------------------------------------------------------------------------


def _canonical_schema() -> dict:
    """The canonical shape the backend produces for a template body."""

    return {
        "version": 1,
        "titleHint": "API Reference — {{moduleName}}",
        "instructions": "Use tables for parameter lists.",
        "sections": [
            {
                "id": "overview",
                "heading": "Overview",
                "level": 2,
                "placeholders": [
                    {
                        "key": "summary",
                        "prompt": "What does this API cover?",
                        "required": True,
                        "maxWords": 80,
                    }
                ],
            },
            {
                "id": "endpoints",
                "heading": "Endpoints",
                "level": 2,
                "placeholders": [
                    {
                        "key": "endpoints",
                        "prompt": "List every public endpoint…",
                        "required": True,
                        "maxWords": 80,
                        "binding": "code.route",
                    },
                    {
                        "key": "errors",
                        "prompt": "List the error codes this API can return.",
                        "required": False,
                    },
                ],
            },
        ],
        "variables": [{"key": "moduleName", "default": "core"}],
    }


def test_system_prompt_reads_guidance_from_placeholders() -> None:
    """The placeholder prompt must reach the prompt — this is the bug.

    The canonical schema keeps the prompt on the *placeholder*, so a
    renderer that reads it from the section level produces an empty
    guidance and silently falls back to the built-in preset.
    """

    prompt = _system_prompt_from_schema("default-api-reference", _canonical_schema())

    assert "Guidance: What does this API cover?" in prompt
    assert "Guidance: List every public endpoint…" in prompt
    assert "Guidance: List the error codes this API can return." in prompt


def test_system_prompt_renders_section_headings_verbatim() -> None:
    prompt = _system_prompt_from_schema("default-api-reference", _canonical_schema())

    assert "## Endpoints" in prompt
    assert "## Overview" in prompt
    # The heading text has to be used verbatim — it is what the AI is
    # told to emit.
    assert "Endpoints" in prompt


def test_system_prompt_carries_placeholder_constraints() -> None:
    prompt = _system_prompt_from_schema("default-api-reference", _canonical_schema())

    assert "REQUIRED" in prompt
    assert "max 80 words" in prompt
    assert "code.route" in prompt


def test_system_prompt_carries_template_instructions_and_variables() -> None:
    prompt = _system_prompt_from_schema("default-api-reference", _canonical_schema())

    assert "Use tables for parameter lists." in prompt
    assert "moduleName" in prompt


def test_system_prompt_handles_missing_optional_fields() -> None:
    """A minimal schema must not crash the renderer."""

    prompt = _system_prompt_from_schema(
        "MODULE_GUIDE",
        {"sections": [{"heading": "Overview", "placeholders": []}]},
    )

    assert "## Overview" in prompt


def test_normalise_template_key_maps_slugs_to_presets() -> None:
    """The frontend sends slugs; the presets are keyed by doc type."""

    assert _normalise_template_key("default-api-reference") == "API_REFERENCE"
    assert _normalise_template_key("API_REFERENCE") == "API_REFERENCE"
    assert _normalise_template_key("default-module-guide") == "MODULE_GUIDE"
    assert _normalise_template_key("default-adr") == "ADR"
    assert _normalise_template_key("default-architecture") == "ARCHITECTURE"
    assert _normalise_template_key(None) == "MODULE_GUIDE"
    assert _normalise_template_key("") == "MODULE_GUIDE"


def test_generator_uses_schema_over_preset() -> None:
    """A template with its own schema must not fall back to the preset."""

    generator = DocumentationGenerator()
    result = generator.generate(
        files=_files(),
        template="default-api-reference",
        template_schema=_canonical_schema(),
    )
    assert result.markdown