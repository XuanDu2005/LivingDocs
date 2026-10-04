# LivingDocs

> AI-assisted documentation maintenance platform for software engineering teams.

LivingDocs keeps documentation synchronised with source code. It integrates
with GitHub to detect drift, uses AST analysis + an LLM to suggest updates,
and routes every change through a two-tier Staff → Manager review workflow
with an immutable change log.

---

## What's in this repo

This monorepo contains the **complete** LivingDocs platform:

| Layer       | Folder                  | Stack                                              |
| ----------- | ----------------------- | --------------------------------------------------- |
| Backend     | `backend/`              | Java 21, Spring Boot 3, PostgreSQL, Flyway, JJWT    |
| AI service  | `ai-service/`           | Python 3.12, FastAPI, AST analysers, LLM providers |
| Frontend    | `frontend/`             | React 18, TypeScript, Vite, axios                   |
| Infra       | `infrastructure/`       | Reserved for IaC                                    |

The platform is divided into the following phases. All phases ship in this
repo:

* **Phase 0** — monorepo foundation + health endpoints.
* **Phase 1** — auth + workspaces + RBAC.
* **Phase 2** — GitHub OAuth, repository linking, webhook ingestion.
* **Phase 3** — documents, templates, versions, drift alerts, review workflow,
  audit log, notifications, knowledge base, AI integration, CI/CD generator.

---

## Architecture

```
                 ┌──────────────────┐
                 │    Frontend      │  React + Vite + TS
                 │   (port 5173)    │
                 └────────┬─────────┘
                          │ HTTP + Bearer JWT
                          ▼
                 ┌──────────────────┐
                 │     Backend      │  Spring Boot 3 / Java 21
                 │   (port 8080)    │  Documents · Reviews · Drift · Audit
                 └────────┬─────────┘
                          │ JDBC + Flyway / REST client
                          ▼
                 ┌──────────────────┐         ┌──────────────────┐
                 │   PostgreSQL     │         │   AI Service     │  FastAPI
                 │   (port 5432)    │         │   (port 8000)    │  LLM + AST + RAG
                 └──────────────────┘         └──────────────────┘
                          │
                          ▼
                 ┌──────────────────┐
                 │     GitHub       │  OAuth · Webhooks · Actions workflow
                 └──────────────────┘
```

---

## Backend modules

| Module       | Responsibility                                                |
| ------------ | -------------------------------------------------------------- |
| `auth`       | Registration / login → JWT bearer                                |
| `workspace`  | Multi-tenant workspaces with MANAGER / MEMBER RBAC            |
| `github`     | OAuth, repository catalog, webhook ingestion, PR mirroring    |
| `document`   | Document CRUD, metadata, status (DRAFT/IN_REVIEW/...)         |
| `template`   | Versioned documentation templates (workspace + global)        |
| `version`    | Immutable document versions + change log + diff + rollback    |
| `review`     | Staff review + Manager approval, with audit trail               |
| `drift`      | Documentation drift alerts (REFERENTIAL/SIGNATURE/SEMANTIC)   |
| `audit`      | Append-only audit log of governance actions                    |
| `notification`| Per-user notifications (drift detected, review assigned, ...)  |
| `link`       | Many-to-many doc ↔ code-entity links                          |
| `code`       | Indexed code entities (AST snapshots)                          |
| `ai`         | AI service client + AI-assisted generate / drift / knowledge   |
| `cicd`       | GitHub Actions workflow file generator                        |
| `admin`      | Platform-wide administrative operations (users, roles)        |
| `health`     | Liveness / readiness probes                                   |

Each module is self-contained (controller, service, DTO, repository, model)
so they can be deleted, replaced or extended independently.

---

## AI service modules

| Module           | Responsibility                                                       |
| ---------------- | --------------------------------------------------------------------- |
| `app.ast`        | Multi-language AST analysers (Python, Java, TypeScript, JavaScript)   |
| `app.llm`        | LLM client + provider abstraction (OpenAI / Anthropic / Ollama / mock)|
| `app.rag`        | In-memory vector store + chunking + cosine search                    |
| `app.services`   | `DocumentationGenerator`, `DriftDetector`, `KnowledgeBase`           |
| `app.api.v1`     | FastAPI routers exposing `/generate`, `/drift`, `/knowledge`, `/ast` |

The mock provider is the default so the entire AI service runs offline.

---

## Frontend pages

| Path                                                       | Purpose                          |
| ---------------------------------------------------------- | -------------------------------- |
| `/login`, `/register`                                      | Authentication                   |
| `/dashboard`, `/profile`                                   | Account hub                      |
| `/workspaces`                                              | Workspace list                   |
| `/workspaces/:id`                                          | Workspace detail + members       |
| `/workspaces/:id/documents`                                | Documentation list               |
| `/workspaces/:id/documents/new`                            | Create document                  |
| `/workspaces/:id/documents/:documentId`                    | Document view / edit / history   |
| `/workspaces/:id/templates`                                | Template management              |
| `/workspaces/:id/drift`                                    | Drift alerts                     |
| `/workspaces/:id/reviews`                                  | Staff / Manager review queue     |
| `/workspaces/:id/knowledge`                                | Knowledge-base search & indexing |
| `/workspaces/:id/health`                                   | Documentation health dashboard   |
| `/admin/users`                                             | Platform-wide user management    |

---

## Running locally

### Prerequisites

* Docker 24+ and Docker Compose v2.
* *OR* Node.js 20+, Python 3.12+, JDK 21 + Maven 3.9+ for local development.

### With Docker (recommended)

```bash
cp .env.example .env
docker compose up --build
```

| Service             | URL                                            |
| ------------------- | ---------------------------------------------- |
| Frontend            | http://localhost:5173                          |
| Backend             | http://localhost:8080                          |
| Backend Swagger     | http://localhost:8080/swagger-ui.html          |
| AI Service          | http://localhost:8000                          |
| AI Service Swagger | http://localhost:8000/docs                     |
| PostgreSQL         | localhost:5432                                 |

### Per-service

```bash
cd backend && mvn spring-boot:run
cd ai-service && uvicorn app.main:app --reload --port 8000
cd frontend && npm install && npm run dev
```

---

## Environment variables

See `.env.example`. The most important additions over earlier phases:

| Variable                          | Purpose                                              |
| --------------------------------- | ---------------------------------------------------- |
| `AI_SERVICE_BASE_URL`             | URL the backend uses to reach the AI service         |
| `AI_SERVICE_ENABLED`              | Disable AI integration to fall back to manual flows  |
| `AI_SERVICE_CONFIDENCE_THRESHOLD` | Threshold below which AI suggestions are flagged     |
| `LLM_PROVIDER`                    | `openai` / `anthropic` / `ollama` / `mock`           |
| `LLM_API_KEY` / `LLM_API_BASE`    | Provider credentials                                 |
| `LLM_MODEL`                       | Default chat model                                   |
| `EMBEDDING_PROVIDER` / `EMBEDDING_MODEL` | Embedding model for RAG                        |
| `APP_PUBLIC_URL`                  | URL exposed in generated GitHub Actions YAML         |

---

## Testing

```bash
# Backend
cd backend
mvn test

# AI service
cd ai-service
pytest
```

Both suites boot in isolation; the backend uses H2 in PostgreSQL-compat
mode, and the AI service runs the mock LLM provider.

---

## Development workflow

1. Branch from `main` for every change.
2. Keep modules self-contained — never reach across module boundaries into another module's internals.
3. New features must add a new module under `backend/src/main/java/com/livingdocs/modules/` rather than modifying existing modules.
4. Never commit secrets. Use environment variables and `.env.example` for documentation.
5. Run `docker compose up --build` before opening a PR to verify the full stack still starts cleanly.

---

## License

Proprietary — internal project.