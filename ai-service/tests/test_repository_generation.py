"""Tests for the repository-level documentation generation.

The repository-level flow is the answer to "describe this repository",
not "describe this module". The tests therefore check two things the
per-module tests cannot: that the endpoint exists and accepts a
structured snapshot, and that the snapshot actually reaches the prompt.
"""

from __future__ import annotations

import json

from fastapi.testclient import TestClient

from app.main import app
from app.services.repository_generator import (
    RepositoryDocumentationGenerator,
    _system_prompt_for_template,
    _user_prompt_for,
)

client = TestClient(app)


def _snapshot() -> dict:
    return {
        "full_name": "acme/widgets",
        "description": "A widget service.",
        "default_branch": "main",
        "tree": [
            "README.md",
            "package.json",
            "src/index.ts",
            "src/api/routes.ts",
            "src/domain/widget.ts",
        ],
        "languages": {"typescript": 3, "json": 1},
        "directories": {"src": 3},
        "manifests": {"package.json": '{"name":"widgets","scripts":{"start":"node dist/main.js"}}'},
        "entry_points": ["src/index.ts"],
        "key_files": [
            {
                "path": "src/index.ts",
                "content": "export function main() {}\n",
                "language": "typescript",
            }
        ],
        "total_files": 5,
        "truncated": False,
    }


# ---------------------------------------------------------------------------
# The prompt
# ---------------------------------------------------------------------------


def test_user_prompt_carries_the_repository_snapshot() -> None:
    """The manifest and the tree must reach the prompt.

    A repository overview cannot be written from a class name — the
    model needs the layout and the dependency manifest to say anything
    true about "how do I run this".
    """

    prompt = _user_prompt_for(_snapshot(), "README", None)

    assert "acme/widgets" in prompt
    assert "package.json" in prompt
    assert "src/domain/widget.ts" in prompt
    assert "src/index.ts" in prompt


def test_user_prompt_includes_the_focus_hint() -> None:
    prompt = _user_prompt_for(_snapshot(), "README", "Focus on the API layer")
    assert "Focus on the API layer" in prompt


def test_system_prompt_uses_the_doc_type_preset() -> None:
    prompt = _system_prompt_for_template("ARCHITECTURE", None)
    assert "components" in prompt.lower()
    assert "trust boundaries" in prompt.lower()


def test_system_prompt_prefers_the_workspace_template_schema() -> None:
    schema = {
        "version": 1,
        "name": "Onboarding Guide",
        "instructions": "Keep the steps copy-pasteable.",
        "sections": [
            {
                "id": "setup",
                "heading": "Setup",
                "level": 2,
                "placeholders": [
                    {"key": "setup", "prompt": "How do I run it?", "required": True}
                ],
            }
        ],
    }
    prompt = _system_prompt_for_template("ONBOARDING_GUIDE", schema)
    assert "Keep the steps copy-pasteable." in prompt
    assert "## Setup" in prompt


# ---------------------------------------------------------------------------
# The generator
# ---------------------------------------------------------------------------


def test_generator_returns_markdown_and_grounding() -> None:
    generator = RepositoryDocumentationGenerator()
    result = generator.generate(repository=_snapshot(), template="README")

    assert result.markdown
    assert 0.0 <= result.confidence <= 1.0
    files = {s["file"] for s in result.sources}
    assert "package.json" in files
    assert "src/index.ts" in files


def test_generator_notes_an_empty_snapshot() -> None:
    """An empty repository must say so instead of pretending to know."""

    generator = RepositoryDocumentationGenerator()
    result = generator.generate(
        repository={"full_name": "acme/empty", "tree": [], "key_files": []},
        template="README",
    )

    assert result.notes
    assert any("empty" in note.lower() for note in result.notes)


# ---------------------------------------------------------------------------
# The endpoint
# ---------------------------------------------------------------------------


def test_generate_repository_endpoint_with_mock_provider() -> None:
    response = client.post("/api/v1/generate/repository", json={
        "repository": _snapshot(),
        "template": "README",
    })
    assert response.status_code == 200
    body = response.json()
    assert body["markdown"]
    assert 0.0 <= body["confidence"] <= 1.0


def test_generate_repository_endpoint_accepts_template_schema() -> None:
    schema = {
        "version": 1,
        "name": "Architecture",
        "sections": [
            {
                "id": "components",
                "heading": "Components",
                "level": 2,
                "placeholders": [{"key": "components", "prompt": "Name them."}],
            }
        ],
    }
    response = client.post("/api/v1/generate/repository", json={
        "repository": _snapshot(),
        "template": "ARCHITECTURE",
        "template_schema": json.dumps(schema),
    })
    assert response.status_code == 200
    assert response.json()["markdown"]


def test_generate_repository_endpoint_rejects_non_object_schema() -> None:
    response = client.post("/api/v1/generate/repository", json={
        "repository": _snapshot(),
        "template": "README",
        "template_schema": json.dumps(["not", "an", "object"]),
    })
    assert response.status_code == 400


def test_generate_repository_endpoint_requires_the_internal_token(
    monkeypatch,
) -> None:
    """The snapshot carries the source of a repository — it must be guarded."""

    monkeypatch.setenv("AI_SERVICE_INTERNAL_TOKEN", "s3cret")

    response = client.post("/api/v1/generate/repository", json={
        "repository": _snapshot(),
        "template": "README",
    })
    assert response.status_code == 401
