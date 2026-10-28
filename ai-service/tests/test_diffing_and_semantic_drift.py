"""Tests for the diff builder and the LLM-assisted semantic drift analyzer."""

from __future__ import annotations

import json
from typing import List

from app.llm import LLMClient
from app.services.diffing import unified_diff
from app.services.semantic_drift import (
    SemanticDriftAnalyzer,
    _parse_findings,
    _to_finding,
)


# ---------------------------------------------------------------------------
# unified diff
# ---------------------------------------------------------------------------


def test_unified_diff_reports_added_and_removed_lines() -> None:
    before = [("a.py", "alpha\nbeta\ngamma\n")]
    after = [("a.py", "alpha\nbeta\nDELTA\ngamma\n")]

    diff = unified_diff(before, after)

    assert "+DELTA" in diff
    assert "--- a.py" in diff
    assert "+++ a.py" in diff
    assert "@@" in diff


def test_unified_diff_is_empty_for_identical_files() -> None:
    files = [("a.py", "same\ncontent\n")]

    assert unified_diff(files, files) == ""


def test_unified_diff_reports_new_file_as_full_addition() -> None:
    diff = unified_diff([], [("new.py", "line one\nline two\n")])

    assert "+++ new.py" in diff
    assert "+line one" in diff
    assert "+line two" in diff


def test_unified_diff_reports_deleted_file_as_full_removal() -> None:
    diff = unified_diff([("old.py", "line one\n")], [])

    assert "--- old.py" in diff
    assert "+++ /dev/null" in diff
    assert "-line one" in diff


def test_unified_diff_handles_multiple_files() -> None:
    before = [("a.py", "one\n"), ("b.py", "two\n")]
    after = [("a.py", "one changed\n"), ("b.py", "two\n")]

    diff = unified_diff(before, after)

    assert "a.py" in diff
    # b.py is unchanged and must not produce a section.
    assert diff.count("--- b.py") == 0


def test_unified_diff_truncates_very_long_diffs() -> None:
    before = [("big.py", "".join(f"line {i}\n" for i in range(5000)))]
    after = [("big.py", "".join(f"CHANGED {i}\n" for i in range(5000)))]

    diff = unified_diff(before, after, max_lines=200)

    assert len(diff.splitlines()) <= 201
    assert "truncated" in diff


# ---------------------------------------------------------------------------
# semantic drift — finding parsing
# ---------------------------------------------------------------------------


def test_parse_findings_accepts_plain_json_array() -> None:
    raw = json.dumps([
        {
            "drift_kind": "SEMANTIC",
            "severity": "HIGH",
            "title": "Stale parameter",
            "description": "The doc mentions a parameter that no longer exists",
            "confidence": 0.9,
            "evidence": {"diff": "@@ -1 +1 @@"},
            "suggestion": "Remove the parameter",
        }
    ])

    findings = _parse_findings(raw)

    assert len(findings) == 1
    assert findings[0].drift_kind == "SEMANTIC"
    assert findings[0].severity == "HIGH"
    assert findings[0].confidence == 0.9
    assert findings[0].suggestion == "Remove the parameter"


def test_parse_findings_tolerates_fenced_code_block() -> None:
    raw = (
        "Here are the findings:\n"
        "```json\n"
        + json.dumps([{"drift_kind": "SEMANTIC", "severity": "LOW", "title": "t",
                       "description": "d", "confidence": 0.7}])
        + "\n```\n"
        "Let me know if you need more detail."
    )

    findings = _parse_findings(raw)

    assert len(findings) == 1
    assert findings[0].title == "t"


def test_parse_findings_accepts_wrapped_findings_object() -> None:
    raw = json.dumps({
        "findings": [
            {"drift_kind": "REFERENTIAL", "severity": "MEDIUM", "title": "t",
             "description": "d", "confidence": 0.6}
        ]
    })

    findings = _parse_findings(raw)

    assert len(findings) == 1
    assert findings[0].drift_kind == "REFERENTIAL"


def test_parse_findings_ignores_unparseable_input() -> None:
    assert _parse_findings("not json at all") == []
    assert _parse_findings("") == []


def test_to_finding_clamps_confidence_to_valid_range() -> None:
    finding = _to_finding({"confidence": 5.0, "title": "t"})
    assert finding.confidence == 1.0

    finding = _to_finding({"confidence": -3.0, "title": "t"})
    assert finding.confidence == 0.0


def test_to_finding_defaults_kind_and_severity() -> None:
    finding = _to_finding({"title": "t", "confidence": 0.5})

    assert finding.drift_kind == "SEMANTIC"
    assert finding.severity == "MEDIUM"


def test_to_finding_ignores_non_dict_evidence() -> None:
    finding = _to_finding({"title": "t", "evidence": "a string"})

    assert finding.evidence == {}


# ---------------------------------------------------------------------------
# semantic drift — analyzer
# ---------------------------------------------------------------------------


class _StubLLM(LLMClient):
    """LLM client that returns a canned answer and records the prompts."""

    def __init__(self, answer: str) -> None:
        self.answer = answer
        self.last_system_prompt = ""
        self.last_user_prompt = ""

    def generate_documentation(self, *, system_prompt: str, user_prompt: str):
        self.last_system_prompt = system_prompt
        self.last_user_prompt = user_prompt
        from app.llm.schemas import GenerationResponse

        return GenerationResponse(
            text=self.answer,
            model="stub",
            provider="stub",
            input_tokens=10,
            output_tokens=20,
        )


def test_analyzer_filters_out_low_confidence_findings() -> None:
    llm = _StubLLM(json.dumps([
        {"drift_kind": "SEMANTIC", "severity": "HIGH", "title": "keep",
         "description": "d", "confidence": 0.9},
        {"drift_kind": "SEMANTIC", "severity": "LOW", "title": "drop",
         "description": "d", "confidence": 0.2},
    ]))
    analyzer = SemanticDriftAnalyzer(llm, min_confidence=0.5)

    result = analyzer.analyze(
        diff_text="@@ -1 +1 @@\n-old\n+new",
        document_markdown="# Doc\n\nOld behaviour.",
        document_title="Doc",
    )

    assert [f.title for f in result.findings] == ["keep"]


def test_analyzer_returns_error_for_empty_diff() -> None:
    llm = _StubLLM("[]")
    analyzer = SemanticDriftAnalyzer(llm)

    result = analyzer.analyze(diff_text="   ", document_markdown="# Doc")

    assert result.findings == []
    assert result.error


def test_analyzer_passes_the_diff_to_the_model() -> None:
    llm = _StubLLM("[]")
    analyzer = SemanticDriftAnalyzer(llm)

    analyzer.analyze(
        diff_text="@@ -1 +1 @@\n-old\n+new",
        document_markdown="# Doc\n\nBehaviour.",
        document_title="My Doc",
    )

    assert "@@ -1 +1 @@" in llm.last_user_prompt
    assert "My Doc" in llm.last_user_prompt
    assert "Behaviour." in llm.last_user_prompt
