# LivingDocs — Architecture & Operations Guide

This document describes how the platform actually behaves at runtime: the
authorization model, the generation pipeline, the governance rules that
gate automated publishing, and the operations that keep secrets safe.

It complements the [README](./README.md), which covers the repo layout and
the local setup.

---

## 1. The two-axis authorization model

LivingDocs has two **independent** axes of authority. Both must be
satisfied to perform a governance action.

### Platform role axis

Assigned by an administrator, stored in `user_roles`.

| Role             | Capabilities                                                        |
| ---------------- | ------------------------------------------------------------------- |
| `DEVELOPER`      | Read and contribute. Cannot review or approve.                       |
| `STAFF`          | First-tier (technical) review.                                      |
| `TECHNICAL_LEAD` | Staff duties **plus** Manager duties — can approve and publish.     |
| `MANAGER`        | Final approval, publishing, workspace administration.               |
| `ADMIN`          | Full platform access.                                                |

### Workspace role axis

Assigned per workspace, stored in `workspace_members`.

| Role     | Capabilities                                              |
| -------- | --------------------------------------------------------- |
| `MEMBER` | Read and contribute.                                       |
| `MANAGER` | Workspace administration (members, repository connections). |

### The AND rule

To pass a review or approval guard the caller needs **both**:

* a platform role that grants the capability, **and**
* membership in the workspace the content lives in.

A platform `ADMIN` who is not a member of a workspace cannot approve
content in it. The workspace role is what proves the person has a stake in
that workspace's content, so the platform role is never a backdoor around
per-workspace scoping.

`TECHNICAL_LEAD` is deliberately **not** given workspace administration.
Managing members and repository connections stays with the workspace
`MANAGER`.

### Where this is enforced

`common/security/AuthorizationService.java` is the single source of truth.
Every review, approval, publish and rollback path routes through it:

| Method                          | Requires                                       |
| ------------------------------- | ---------------------------------------------- |
| `requireStaffReviewer`          | platform ∈ {STAFF, TECHNICAL_LEAD, MANAGER, ADMIN} **and** member |
| `requireManagerApprover`        | platform ∈ {TECHNICAL_LEAD, MANAGER, ADMIN} **and** member |
| `requireWorkspaceManager`       | workspace role = `MANAGER` (platform role does not substitute) |
| `requireWorkspaceMember`        | member                                          |

`GET /workspaces/{id}/capabilities` returns the caller's effective
capabilities so the frontend never renders a button the API would reject.

---

## 2. The generation pipeline

Generation is **asynchronous**. The HTTP call returns `202 Accepted` with
a job id; the LLM work happens on a Kafka worker.

```
POST /workspaces/{id}/documents/{docId}/generate?template=README
        │
        │  202 { jobId, status: "ACCEPTED" }
        ▼
   ┌─────────┐   DocGenerationRequestedEvent    ┌──────────────┐
   │ Backend │ ───────────────────────────────▶ │ Kafka        │
   └─────────┘                                  └──────┬───────┘
                                                      │
                            ┌─────────────────────────▼───────────┐
                            │ DocGenerationEventListener          │
                            │  · calls the AI service             │
                            │  · applies the governance rules     │
                            └─────────────────────────┬───────────┘
                                                      │
                     ┌────────────────────────────────┼─────────────────┐
                     ▼                                ▼                 ▼
              new version PENDING            auto-published        job row
              (review queue)                 (AUTO_APPLY)          (status)
```

### Polling

```bash
GET /workspaces/{id}/documents/{docId}/generation-status
```

Returns the most recent job for the document:

```json
{
  "id": "651d109f-…",
  "documentId": "33333333-…",
  "status": "SUCCEEDED",
  "template": "README",
  "documentVersionId": "5c9e5081-…",
  "promptTokens": 179,
  "completionTokens": 126,
  "createdAt": "…",
  "completedAt": "…"
}
```

`status` is one of `RUNNING`, `SUCCEEDED`, `FAILED`, `SKIPPED`, `NONE`.
The frontend polls this every 2 s and reloads the document when the job
reaches a terminal state.

### Token accounting

Every run writes to `ai_usage_stats`, so the workspace's daily and
monthly budgets are metered from real usage rather than estimated.

---

## 3. Governance rules

`modules/ai/settings/GovernancePolicyService.java` evaluates every policy
decision. The same service answers the read-only
`GET /workspaces/{id}/governance` endpoint, so what the settings form
shows and what the runtime enforces can never drift apart.

### Doc-type workflow

A platform policy per `doc_type` decides the fate of generated content:

| Workflow        | Effect                                                  |
| --------------- | ------------------------------------------------------- |
| `AUTO_APPLY`    | Published automatically (subject to the confidence gate) |
| `MANAGER_REVIEW`| Queued in the review queue at the Staff tier            |

**Precedence:** an explicit per-doc-type policy is the most specific
rule and therefore wins over the workspace's `requireManagerApproval`
flag. The workspace flag is only the fallback for doc types the platform
has no policy for.

### Confidence gate

A generation whose confidence falls **below** the workspace threshold is
never auto-applied — it is always routed to a human. A generation with
**no** confidence score is treated as untrusted.

The threshold is inclusive: a result exactly at the threshold passes.

### Token budget

`requireBudget` refuses a generation when the workspace has exhausted its
daily or monthly allowance. The refusal surfaces as **HTTP 429**:

```json
{
  "status": 429,
  "error": "Too Many Requests",
  "message": "AI token budget exhausted for this workspace (today: 1220 tokens, month: 1220 tokens). Raise the limit in workspace settings or wait for the next period."
}
```

### Merge gate

`evaluateMergeGate` returns a verdict the CI/CD integration can act on:

* `blocked=true` when the workspace policy is `BLOCK` **and** at least one
  open drift alert is at or above the severity threshold.
* `blocked=false` otherwise, with a `reason` that explains which alerts
  were considered.

```bash
GET /workspaces/{id}/governance/merge-gate?pullRequestId=…
```

### End-to-end example

With a `README` policy of `AUTO_APPLY` and a confidence threshold of
`0.50`:

| Generation                       | Result                       |
| -------------------------------- | ---------------------------- |
| `README`, confidence `0.55`     | published automatically      |
| `MODULE_GUIDE`, confidence `0.55`| queued for review (PENDING)  |

Raise the threshold to `0.70` and the same `README` generation is routed
to a human instead.

---

## 4. Review queues

`GET /workspaces/{workspaceId}/reviews/pending-on-me` returns everything
the caller may act on, split by tier:

| Field         | Contents                                                        |
| ------------- | --------------------------------------------------------------- |
| `staffQueue`  | versions awaiting a technical (Staff) review                    |
| `managerQueue`| versions a Staff reviewer approved, awaiting a Manager decision |
| `publishQueue`| versions approved, ready to be promoted to head                 |

Buckets the caller has no capability for come back **empty** rather than
forbidden, so a single request drives the whole page.

Individual queues are also available at `/reviews/staff-queue`,
`/reviews/manager-queue` and `/reviews/publish-queue`.

### The two-tier flow

```
PENDING ──staff-review(APPROVED)──▶ IN_REVIEW ──manager-decision(APPROVED)──▶ APPROVED ──publish──▶ PUBLISHED
```

A `REQUEST_CHANGES` verdict at either tier returns the version to
`PENDING` with the reviewer's comment attached.

---

## 5. Drift detection

Drift detection combines mechanical analysis with LLM reasoning.

1. **Diff** — `ai-service/app/services/diffing.py` builds a unified diff
   between the "before" and "after" source trees. The diff is far cheaper
   in tokens than shipping both trees, and it points at the exact lines
   that moved.
2. **AST analysis** — catches referential drift (a referenced symbol
   disappeared) and signature drift (a signature changed).
3. **Semantic analysis** — `ai-service/app/services/semantic_drift.py`
   asks the LLM to compare the documentation against the diff and report
   places where the doc no longer describes the code accurately.
4. **Threshold** — findings below the workspace's
   `drift_severity_threshold` are filtered out before they become alerts.

The unified diff is stored in the alert's evidence, and the frontend
renders it with the shared diff viewer.

---

## 6. Secrets at rest

### Encryption

OAuth access tokens (GitHub connections and user OAuth identities) are
encrypted with **AES-256-GCM** before they reach the database. The key is
derived from the application secret with SHA-256, so the same value
across restarts decrypts correctly while the key never appears in source
control.

`common/crypto/EncryptedStringConverter.java` is a JPA
`AttributeConverter`, which means:

* the entity always holds the **plaintext** — no call site has to
  remember to decrypt;
* the database always holds the **ciphertext** — no code path can
  accidentally persist a raw token.

Ciphertext layout (Base64): `[12-byte IV][ciphertext+16-byte GCM tag]`,
prefixed with `enc:v1:`. The prefix is how the system tells ciphertext
from a legacy plaintext row.

### Migrating legacy plaintext rows

`common/crypto/GithubTokenEncryptionRunner.java` runs on every boot and
rewrites any plaintext token as ciphertext. It is a no-op after the first
pass.

It uses explicit native `UPDATE` statements rather than loading and
re-saving the entities. That is deliberate: Hibernate's dirty checking
compares the loaded value with the current one, and because they are the
same `String` instance, no `UPDATE` would be issued and the row would
keep its plaintext forever.

### Internal service authentication

The AI service is internal: it holds no user credentials, but it does hold
workspace API keys and serves the platform's LLM budget. When
`AI_SERVICE_INTERNAL_TOKEN` is set, every call must present the matching
token in the `X-Ai-Internal-Token` header.

Leaving the token unset keeps the service open — that is the behaviour of
a local dev setup where the service is not reachable from outside the
compose network. **Production deployments should always set it.**

---

## 7. Configuration reference

### Governance

| Setting                          | Where                | Effect                                        |
| -------------------------------- | -------------------- | --------------------------------------------- |
| `require_manager_approval`       | `workspace_settings` | fallback workflow for unlisted doc types      |
| `ai_confidence_threshold`        | `workspace_settings` | minimum confidence for auto-apply             |
| `merge_policy_critical`          | `workspace_settings` | `WARN` or `BLOCK`                              |
| `drift_severity_threshold`       | `workspace_settings` | minimum severity that becomes an alert        |
| `daily_token_limit`              | `workspace_settings` | per-day AI token allowance (`NULL` = unlimited) |
| `monthly_token_limit`            | `workspace_settings` | per-month AI token allowance                  |
| `platform_doc_type_policies`     | platform settings   | per-doc-type `AUTO_APPLY` / `MANAGER_REVIEW`   |

### Environment

| Variable                        | Purpose                                            |
| ------------------------------- | -------------------------------------------------- |
| `AI_SERVICE_BASE_URL`           | URL the backend uses to reach the AI service        |
| `AI_SERVICE_ENABLED`            | disable AI integration to fall back to manual flows |
| `AI_SERVICE_INTERNAL_TOKEN`     | shared secret for backend → AI service calls        |
| `LLM_PROVIDER`                  | `openai` / `anthropic` / `ollama` / `mock`           |
| `LLM_API_KEY` / `LLM_API_BASE`  | provider credentials                               |
| `LLM_MODEL`                     | default chat model                                 |
| `APP_SECURITY_JWT_SECRET`       | app secret; also the seed for the AES key          |

---

## 8. Testing

```bash
# Backend (Java 21 + JUnit 5)
cd backend && mvn test

# AI service (Python 3.12 + pytest)
cd ai-service && pytest
```

When Maven is not installed locally, the same suites run in a container:

```bash
cd backend
docker run --rm -v "${PWD}:/app" -w /app maven:3.9-eclipse-temurin-21 \
  mvn -B -ntp test

cd ai-service
docker run --rm -v "${PWD}:/app" -w /app --entrypoint python \
  livingdocs-ai-service -m pytest tests/ -q
```

### What the suites cover

| Suite                       | Focus                                                    |
| --------------------------- | -------------------------------------------------------- |
| `AuthorizationServiceTest`  | the AND rule, per-tier capabilities, workspace management |
| `GovernancePolicyServiceTest`| doc-type precedence, confidence gate, budget, merge gate |
| `EncryptedStringConverterTest` | round-trip, IV freshness, legacy plaintext, key rotation |
| `DocumentVersionServiceDiffTest` | unified diff format                                  |
| `test_diffing_and_semantic_drift.py` | diff building, LLM finding parsing, confidence filtering |
| `test_ast.py`               | Python / Java / TypeScript analysers                     |

---

## 9. Database migrations

| Version | Name                                   | Purpose                                        |
| ------- | -------------------------------------- | ---------------------------------------------- |
| V29     | `doc_generation_jobs`                  | tracks async generation jobs                   |
| V30     | `two_axis_role_model`                  | normalises `workspace_members.role`, grants `TECHNICAL_LEAD` permissions |
| V31     | `widen_encrypted_token_columns`        | widens token columns so ciphertext always fits |

### Why some migrations are not Flyway migrations

Flyway runs before the Spring context is fully initialised, so the
`AesGcmCipher` bean is not available yet. Worse, PostgreSQL's `pgcrypto`
does not expose raw AES-GCM — `pgp_sym_encrypt` produces an OpenPGP
container the Java cipher cannot read. Re-implementing AES-GCM in PL/pgSQL
is neither possible nor desirable, so token encryption happens in an
application runner instead.
