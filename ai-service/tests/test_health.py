"""Test suite for the AI service."""

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_health_endpoint_returns_up() -> None:
    """The health endpoint must return 200 and the expected payload."""
    response = client.get("/api/v1/health")

    assert response.status_code == 200
    body = response.json()
    assert body == {"status": "UP", "service": "livingdocs-ai-service"}


def test_root_endpoint_hints_at_docs() -> None:
    """The root endpoint advertises where to find Swagger UI."""
    response = client.get("/")

    assert response.status_code == 200
    body = response.json()
    assert body["docs"] == "/docs"
    assert body["health"] == "/api/v1/health"


def test_ast_languages_endpoint() -> None:
    response = client.get("/api/v1/ast/languages")
    assert response.status_code == 200
    body = response.json()
    for lang in ("python", "java", "typescript", "javascript"):
        assert lang in body["languages"]


def test_parse_endpoint_for_python() -> None:
    response = client.post("/api/v1/ast/parse", json={
        "path": "demo.py",
        "content": "class Greeter:\n    def hi(self):\n        return 'hi'\n",
    })
    assert response.status_code == 200
    body = response.json()
    assert body["language"] == "python"
    names = {e["simple_name"] for e in body["entities"]}
    assert "Greeter" in names
    assert "hi" in names


def test_generate_endpoint_with_mock_provider() -> None:
    response = client.post("/api/v1/generate", json={
        "files": [{"path": "calc.py", "content": "def add(a, b): return a + b"}],
        "template": "MODULE_GUIDE",
    })
    assert response.status_code == 200
    body = response.json()
    assert body["markdown"]
    assert 0.0 <= body["confidence"] <= 1.0


def test_drift_endpoint_detects_referential_drift() -> None:
    response = client.post("/api/v1/drift", json={
        "files_before": [{"path": "calc.py", "content": "class Foo:\n    pass\n"}],
        "files_after": [],
        "document_markdown": "Use `Foo` to add two numbers.",
    })
    assert response.status_code == 200
    body = response.json()
    assert body["findings"]


def test_knowledge_search_after_index() -> None:
    index = client.post("/api/v1/knowledge/index", json={
        "document_id": "doc-1",
        "title": "Calculator",
        "body": "# Calculator\n\nUse the `add` function to sum two integers.",
    })
    assert index.status_code == 200
    search = client.post("/api/v1/knowledge/search", json={
        "query": "how to add two numbers",
        "top_k": 3,
    })
    assert search.status_code == 200
    body = search.json()
    assert body["hits"]