# CI/CD — LivingDocs

This document explains how the platform is built, tested and deployed, and
how to configure the deployment for your own environment.

It answers the project's non-functional requirement:

> *CI/CD pipelines shall automatically build, test, and deploy services.*

---

## 1. The two workflows

| Workflow | File | Trigger | What it does |
| --- | --- | --- | --- |
| **CI** | `.github/workflows/ci.yml` | every push and pull request | build, test, validate, audit |
| **Deploy** | `.github/workflows/deploy.yml` | push to `main`, a `v*` tag, or a manual dispatch | publish images, roll out, smoke test |

The two are deliberately separate. CI runs constantly and must stay fast
and free of credentials. A deploy runs rarely and touches a real
environment. Mixing them would put a production SSH key in the path of
every pull request.

---

## 2. The CI pipeline

The CI workflow is split into independent jobs, so one failure never
hides the state of the others.

| Job | What it verifies | How |
| --- | --- | --- |
| `backend` | the Spring Boot service compiles, its 371 unit tests pass, and it packages | `mvn verify` + `mvn package` |
| `ai-service` | the FastAPI service passes its tests | `pytest` against a real PostgreSQL + pgvector |
| `frontend` | the TypeScript compiles and the production bundle builds | `npm ci` → `npm run typecheck` → `npm run build` |
| `migrations` | every Flyway script applies to an empty database and its checksum still matches | `flyway:migrate` → `flyway:validate` → `flyway:info` |
| `docker` | the three images the compose file runs actually build | `docker/build-push-action` with a per-service GHA cache |
| `security` | the dependency tree has no known exploitable vulnerability | `pip-audit`, `npm audit`, OWASP dependency-check |
| `ci-passed` | every job above succeeded | a single gate job |

### Why the AI service needs a real database

The knowledge base is not an in-memory stub — it is PostgreSQL with the
`pgvector` extension, so the tests exercise the real query path
(cosine distance over an HNSW index, the `ON CONFLICT` upsert, the
prefix delete). A mock would pass while the real deployment failed.

The CI job provisions the `knowledge_chunks` table the same way the
backend's `V47__add_pgvector_knowledge_base.sql` migration does. The AI
service has no migration of its own — the schema belongs to the backend.

### Why the migrations are validated separately

`flyway:validate` re-reads the applied migrations and compares their
checksums against the files in the repository. A mismatch means a
migration that has already run somewhere was edited afterwards. That is
the single most damaging mistake in a migration-driven schema: every
environment that applied the old version now disagrees with the code.

The job therefore runs `flyway:migrate` on an empty database first — a
migration that only works against an existing schema is caught there.

### The CI gate

`ci-passed` is the job branch protection points at. It re-checks every
result and fails if any of them is not `success`, which gives a single
required status check instead of five.

---

## 3. The deploy pipeline

### Environments

| Environment | Trigger | Image tag |
| --- | --- | --- |
| `staging` | push to `main` | `staging-<sha>` |
| `production` | push a `v*` tag, or a manual dispatch with the confirmation flag | the tag, e.g. `v1.0.0` |

A manual production rollout needs the `production` confirmation flag.
The workflow refuses to deploy to production without it, so a mistyped
dispatch cannot promote a build to production by accident.

### The rollout

1. **resolve** — decides the environment and the tag.
2. **build-and-push** — builds the three images for `linux/amd64` and
   `linux/arm64` and pushes them to the GitHub container registry.
3. **deploy** — copies the compose file to the host, pulls the images,
   starts the stack, waits for every service to report healthy, and runs
   the smoke test.

### The smoke test

The rollout is not complete until the deployed stack answers:

| Check | Expectation |
| --- | --- |
| `GET /api/v1/health` (backend) | 200 |
| `GET /api/v1/health` (AI service) | 200 |
| `GET /` (frontend) | 200 |
| `GET /v3/api-docs` (backend) | 200 |

The OpenAPI document is checked deliberately: it is the contract that
third parties consume, so a deployment that serves the app but breaks
the API description is not a successful deployment.

### Image naming

The compose file resolves its images from two variables:

```yaml
image: ${IMAGE_REPOSITORY:-livingdocs}/backend:${IMAGE_TAG:-latest}
```

| Variable | Local development | Deployment |
| --- | --- | --- |
| `IMAGE_REPOSITORY` | unset → `livingdocs` | `ghcr.io/<owner>` |
| `IMAGE_TAG` | unset → `latest` | the release tag |

This is what lets one compose file serve both places: locally it builds
and runs the images from source, on the host it pulls the published ones.

---

## 4. Configuring a deployment

### 4.1 Repository secrets

Create a GitHub environment called `staging` and one called `production`
(*Settings → Environments*). Put the secrets below in the environment,
not in the repository — a repository secret is available to every
workflow, an environment secret only to the one that names it.

| Secret | Purpose |
| --- | --- |
| `DEPLOY_HOST` | the hostname or IP of the Docker host |
| `DEPLOY_USER` | the SSH user on that host |
| `DEPLOY_PATH` | the directory the compose file lives in |
| `DEPLOY_SSH_KEY` | the private key used to reach the host |

### 4.2 Repository variables

| Variable | Purpose |
| --- | --- |
| `BACKEND_URL` | public URL of the deployed backend |
| `AI_SERVICE_URL` | public URL of the deployed AI service |
| `FRONTEND_URL` | public URL of the deployed frontend |
| `VITE_API_BASE_URL` | API base URL baked into the frontend bundle |

### 4.3 The deployment host

The host needs Docker with the Compose plugin, and a `.env` file next to
the compose file holding the runtime configuration:

```bash
# ~/.livingdocs/.env  (on the deployment host)
POSTGRES_PASSWORD=<a real password>
APP_JWT_SECRET=<openssl rand -base64 48>
AI_SERVICE_INTERNAL_TOKEN=<openssl rand -base64 48>
GITHUB_OAUTH_CLIENT_ID=...
GITHUB_OAUTH_CLIENT_SECRET=...
# ... the rest of the keys from .env.example
```

The host must also be able to reach the GitHub container registry:

```bash
echo "$GITHUB_TOKEN" | docker login ghcr.io -u <owner> --password-stdin
```

### 4.4 Protecting production

In the `production` environment, enable *Required reviewers*. The
deploy job then waits for a human approval before it touches the host.
This is the last gate between a green build and a live system.

### 4.5 Branch protection

Point the branch protection rule for `main` at the `ci-passed` check.
That gives every pull request one required status, and the other five
jobs still report their own result for debugging.

---

## 5. Running the pipeline locally

The pipeline is not a black box — every step can be reproduced on a
developer machine.

```bash
# Backend
cd backend && mvn verify

# AI service (needs a PostgreSQL with pgvector)
cd ai-service && pip install -r requirements.txt && pytest -q --cov=app

# Frontend
cd frontend && npm ci && npm run typecheck && npm run build

# Migrations
cd backend
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/livingdocs
export SPRING_DATASOURCE_USERNAME=livingdocs
export SPRING_DATASOURCE_PASSWORD=change_me
mvn flyway:migrate && mvn flyway:validate

# The whole stack
docker compose up -d --build
docker compose ps
```

---

## 6. Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `backend` job fails on `mvn verify` | a unit test broke | run `mvn verify` locally; the failing test is named in the log |
| `ai-service` job fails to connect | the PostgreSQL service did not become ready | the job waits for `pg_isready`; if it still fails, check the service container log |
| `migrations` job fails at `validate` | a migration file was edited after it had been applied | restore the original file, or add a new migration instead — never edit an applied one |
| `docker` job fails on `frontend` | `npm ci` could not resolve the lockfile | run `npm ci` locally; if the lockfile is stale, commit an updated `package-lock.json` |
| `security` job cannot reach the NVD | the OWASP feed is rate-limited | the job warns and continues; re-run it later |
| deploy fails at `docker compose pull` | the host is not logged into the registry | `docker login ghcr.io` on the host |
| deploy fails the smoke test | the stack started but a service is unhealthy | `docker compose logs --tail 100` on the host |
