"""Shared pytest fixtures for the AI service test suite.

The internal-token guard
------------------------
The AI service rejects every request that does not carry
``X-Ai-Internal-Token`` once ``AI_SERVICE_INTERNAL_TOKEN`` is set. The
Docker Compose stack sets it, so a test run inside that environment would
fail against the knowledge and AST endpoints while passing on a developer
machine that has no token configured. The fixture below pins the
environment so the behaviour under test does not depend on where the
suite runs.
"""

from __future__ import annotations

import os
import uuid

import pytest


@pytest.fixture(autouse=True)
def _offline_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    """Run the suite against the offline mock provider and an open guard."""

    monkeypatch.setenv("LLM_PROVIDER", "mock")
    monkeypatch.setenv("EMBEDDING_PROVIDER", "mock")
    # An empty token puts the service in "open" mode: the guard accepts
    # every request. The guard's own behaviour is covered by the explicit
    # tests that set a token.
    monkeypatch.setenv("AI_SERVICE_INTERNAL_TOKEN", "")


@pytest.fixture
def workspace_id() -> str:
    """A random workspace UUID, valid for the typed ``workspace_id`` column."""

    return str(uuid.uuid4())


@pytest.fixture
def document_id() -> str:
    """A random document UUID, valid for the typed ``document_id`` column."""

    return str(uuid.uuid4())


@pytest.fixture
def postgres_available() -> bool:
    """Whether a PostgreSQL instance the vector store can reach is up."""

    try:
        import psycopg2  # noqa: F401
    except ImportError:
        return False
    return os.getenv("POSTGRES_HOST", "localhost") is not None
