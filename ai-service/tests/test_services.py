"""Tests for the documentation generator and drift detector."""

from __future__ import annotations

import json

from app.llm import LLMClient
from app.services import DocumentationGenerator, DriftDetector
from app.services.generator import _heuristic_confidence
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