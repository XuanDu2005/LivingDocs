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

`TECHNICAL_LEAD` is a *platform* capability, not a workspace ownership:
it holds `GOVERNANCE_MANAGE`, so it may *read* the governance of every
workspace it belongs to and review there — but changing that governance
still requires owning the workspace (see below). At the workspace it
manages, the Technical Lead configures the governance; in every other
workspace it only sees and reviews.

### Workspace role axis

Assigned per workspace, stored in `workspace_members`.

| Role                  | Capabilities                                                       |
| --------------------- | ------------------------------------------------------------------ |
| `MEMBER`              | Read and contribute.                                                |
| `DOCUMENTATION_OWNER` | Owns the documentation governance: templates, doc-type workflows, governance settings. |
| `MANAGER`             | Workspace administration (members, repository connections) **plus** everything the documentation owner may do. |

`DOCUMENTATION_OWNER` exists because "owns the shape of the
documentation" and "administers the workspace" are genuinely different
responsibilities. A team can hand one person the first without handing
them the second. The role grants nothing on its own — the capability
axis still has to agree (a documentation owner without
`GOVERNANCE_MANAGE` cannot change a knob), so the two axes stay
combined with AND.

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
| `requireDocumentationGovernance`| workspace role ∈ {`MANAGER`, `DOCUMENTATION_OWNER`} |
| `requireWorkspaceMember`        | member                                          |

`GET /workspaces/{id}/capabilities` returns the caller's effective
capabilities — including `workspaceManager` and `documentationOwner` —
so the frontend never renders a button the API would reject.

### The capability axis

Not every capability is tier-shaped. "May read the AI provider
configuration", "may see operational health metrics", "may publish to
GitHub" are *capabilities*, not tiers — they do not line up with the
role ladder. Those live in the `role_permissions` matrix (seeded by
Flyway `V16`, extended by `V36`), and are enforced with
`@RequirePermission`.

| Annotation / guard                          | Requires                            |
| ------------------------------------------- | ----------------------------------- |
| `@RequirePermission({"HEALTH_DASHBOARD_VIEW"})` | the permission code              |
| `PermissionService.requireAnyPermission(...)` | the permission code (service layer) |

`common/security/PermissionService.java` resolves the union of the
permissions granted to every *active* platform role the user holds. A
user with no role holds no permissions, so every guarded endpoint
refuses them — the "no privileges" default is what makes the matrix
meaningful. `common/security/PermissionInterceptor.java` performs the
check before the controller runs; `ADMIN` always passes.

`GET /users/me` returns the caller's effective permission list as
`permissions`, so the frontend can hide what it may not use. The backend
stays the authority — hiding a link is a courtesy, the 403 is the
control.

#### Capability-gated surfaces

| Surface                             | Permission                     |
| ----------------------------------- | ------------------------------ |
| `GET /system-health`                | `HEALTH_DASHBOARD_VIEW`        |
| `GET /workspaces/{id}/audit-logs`   | `AUDIT_LOG_VIEW`               |
| `GET /workspaces/{id}/documents/{docId}/audit-trail` | `AUDIT_LOG_VIEW`   |
| `GET /workspaces/{id}/analytics`    | `ANALYTICS_VIEW`               |
| `GET/PUT /workspaces/{id}/ai-settings` | `AI_CONFIG_READ` / `AI_CONFIG_WRITE` |
| `GET/PUT /workspaces/{id}/ai-settings/threshold` | `GOVERNANCE_VIEW` / `GOVERNANCE_MANAGE` |
| `GET /workspaces/{id}/ai-usage`     | `AI_USAGE_VIEW`                |
| `PUT /workspaces/{id}/ai-usage/limits` | `AI_USAGE_MANAGE`           |
| `GET/PUT /workspaces/{id}/notification-policies` | `NOTIFICATION_POLICY_VIEW` / `…_MANAGE` |
| `GET/POST /workspaces/{id}/integrations` | `INTEGRATION_VIEW` / `INTEGRATION_CONNECT` |
| `GET /workspaces/{id}/governance`   | `GOVERNANCE_VIEW`              |
| `GET /workspaces/{id}/settings`     | `GOVERNANCE_VIEW`              |
| `PUT /workspaces/{id}/settings`     | `GOVERNANCE_MANAGE`            |
| `…/repositories/{id}/commit`, `…/doc-sync/apply` | `SCM_COMMIT`       |

The permission check is **additional** to the workspace check, not a
replacement for it: reading the AI settings still requires workspace
`MANAGER`, and publishing to GitHub still requires workspace membership.
The permission answers *may this tier do this at all*; the workspace
role answers *may this person do it in this workspace*.

`DEVELOPER` holds **no** oversight permission. It keeps
`DOCUMENT_CREATE`, `DOCUMENT_UPDATE` and `AI_RUN_GENERATION` —
contributing is the whole point of the role — but it cannot read
operational data, the provider configuration, the audit trail, or push to
GitHub. `STAFF` may read the health dashboard, the governance policy and
the notification policies (it reviews content against them), but not the
audit trail or the provider configuration.

> **Why `GET /system-health` is gated.** It exposes DB latency, the AI
> service base URL, JVM heap usage, thread count and the job-queue
> depth. That is a deployment fingerprint, not documentation — any
> authenticated user could use it to profile the infrastructure. The
> plain liveness probe at `GET /health` stays open: it returns only a
> status string and is used by the load balancer.

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

A policy per `doc_type` decides the fate of generated content:

| Workflow        | Effect                                                  |
| --------------- | ------------------------------------------------------- |
| `AUTO_APPLY`    | Published automatically (subject to the confidence gate) |
| `MANAGER_REVIEW`| Queued in the review queue at the Staff tier            |

**Precedence**, from the most specific rule to the least:

1. the workspace's own row in `workspace_doc_type_policies`,
2. the platform's row in `platform_doc_type_policies`,
3. the workspace's `requireManagerApproval` flag,
4. the platform's `default_require_manager_approval` flag.

A workspace row only exists for the doc types the workspace actually
overrode — everything else keeps inheriting the platform policy, so
overriding one doc type cannot silently change the others. The overrides
are managed under **Workspace settings → Doc types** and require the
workspace `MANAGER` role plus the `GOVERNANCE_MANAGE` capability; each
change is written to the audit trail.

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

#### The gate is enforced, not merely available

The verdict only matters if something asks for it. LivingDocs answers the
question **"may this pull request merge?"** from one endpoint, and the
generated GitHub Actions workflow is a thin client of it:

```bash
GET /api/v1/cicd/workspaces/{workspaceId}/repositories/{repositoryId}/merge-gate?pullRequestNumber=…
```

```
PR opened
   │
   ▼
GitHub Actions  ──▶  GET /api/v1/cicd/workspaces/{ws}/repositories/{repo}
   │                       /merge-gate?pullRequestNumber=…
   │                       X-LivingDocs-CI-Token: ldci_…
   ▼
MergeGateService
   │
   ├─ 1. scan   → read the PR's changed files from GitHub, run drift
   │               detection on them, file the alerts
   │
   └─ 2. gate   → GovernancePolicyService.evaluateMergeGate(workspaceId, …)
                   reads the workspace's own merge_policy_critical and
                   drift_severity_threshold
   │
   ▼
{ "blocked": true, "policy": "BLOCK", "reason": "…" }
   │
   ▼
workflow exits 1 → the PR check fails → merge is blocked
```

The endpoint (`cicd/service/MergeGateService`) does two things, in order:

1. **Scan** — read the pull request's changed files from the GitHub API
   and run drift detection, so the alerts the gate reasons about are the
   ones this pull request introduces. A failed scan is reported in the
   verdict but does not by itself block the merge.
2. **Gate** — evaluate the workspace's own policy through
   `GovernancePolicyService.evaluateMergeGate`.

The workflow then honours the answer — it exits non-zero when
`blocked=true` and does nothing otherwise.

**The workflow never decides on its own.** It used to take a
`?blockOnCritical=true` query parameter and fail the build itself, which
made the workspace setting decorative: a workflow generated while the
policy was `WARN` kept failing builds, and one generated after the policy
was switched to `BLOCK` kept passing them. Because the workflow now
always asks, the workspace's current policy always applies and the file
never has to be regenerated when the policy changes.

For the same reason, PR drift detection is no longer skipped when
`auto_update_on_pr` is off: under a `BLOCK` policy the scan is what
produces the alerts the gate needs, so skipping it would silently open
the gate.

Three properties this arrangement guarantees:

1. **The policy comes from the workspace, never from the caller.**
   Changing the policy takes effect immediately, without regenerating or
   recommitting the workflow.
2. **The scan runs before the gate is evaluated.** A failed scan is
   reported in `scanNote` and does not by itself block a merge.
3. **The verdict is audited.** Every evaluation writes a
   `merge.gate.evaluate` audit entry, so "why did this PR not merge?" is
   answerable after the fact.

#### The CI token

A CI job cannot present a user JWT — it has no user, and the JWT would
expire. LivingDocs therefore issues a **workspace CI token**:

| Property | Value |
| -------- | ----- |
| Format   | `ldci_` + 32 random bytes, base64url |
| Storage  | SHA-256 hash only — the plaintext is shown once and never again |
| Scope    | one workspace; it cannot act on another workspace's repositories |
| Header   | `X-LivingDocs-CI-Token: ldci_…` |
| Lifecycle| issue / rotate / revoke, at `GET|POST /rotate|DELETE /workspaces/{id}/ci-token` |

Issuing or revoking requires the `GOVERNANCE_MANAGE` capability **and**
the workspace `MANAGER` role: handing out a credential that acts on the
workspace is a workspace-administration decision, not a platform one.

The token is bound to the workspace as a request attribute by
`CiTokenAuthenticationFilter`, and `/api/v1/cicd/**` is `permitAll` at
the Spring Security layer — the filter, not the JWT chain, is what
authenticates those calls. The token is stored in the repository as the
`LIVINGDOCS_CI_TOKEN` secret.

Workspace managers manage all of this from the **Merge gate** tab of the
workspace settings page (`components/MergeGatePanel.tsx`): the effective
policy and current verdict, the CI token (issue / rotate / revoke), and
the generated workflow YAML.

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
| `workspace_doc_type_policies`    | workspace settings  | per-doc-type override of the platform policy    |

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
| `GitHubActionsWorkflowGeneratorTest` | the generated workflow asks for the verdict instead of deciding |
| `MergeGateServiceTest` | scan-then-gate ordering, failed scan, workspace isolation, audit |
| `GitHubActionsWorkflowGeneratorTest` | the generated workflow asks for the verdict instead of deciding |
| `MergeGateServiceTest` | scan-then-gate ordering, failed scan, workspace isolation, audit |
| `GitHubActionsWorkflowGeneratorTest` | the generated workflow asks for the verdict instead of deciding |
| `MergeGateServiceTest` | scan-then-gate ordering, failed scan, workspace isolation, audit |
| `GitHubActionsWorkflowGeneratorTest` | the generated workflow asks for the verdict instead of deciding |
| `MergeGateServiceTest` | scan-then-gate ordering, failed scan, workspace isolation, audit |
| `GitHubActionsWorkflowGeneratorTest` | the generated workflow asks for the verdict instead of deciding |
| `MergeGateServiceTest` | scan-then-gate ordering, failed scan, workspace isolation, audit |
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
| V52     | `documentation_owner_workspace_role`   | adds the `DOCUMENTATION_OWNER` workspace role |
| V52     | `documentation_owner_workspace_role`   | adds the `DOCUMENTATION_OWNER` workspace role and widens `workspace_members.role` |
| V52     | `documentation_owner_workspace_role`   | widens `workspace_members.role` and adds the `DOCUMENTATION_OWNER` value |
| V53     | `drift_resolve_permission`            | adds the `DRIFT_RESOLVE` permission for the review tiers |
| V52     | `documentation_owner_workspace_role`   | adds the `DOCUMENTATION_OWNER` workspace role and widens the role column |

### Why some migrations are not Flyway migrations

Flyway runs before the Spring context is fully initialised, so the
`AesGcmCipher` bean is not available yet. Worse, PostgreSQL's `pgcrypto`
does not expose raw AES-GCM — `pgp_sym_encrypt` produces an OpenPGP
container the Java cipher cannot read. Re-implementing AES-GCM in PL/pgSQL
is neither possible nor desirable, so token encryption happens in an
application runner instead.
