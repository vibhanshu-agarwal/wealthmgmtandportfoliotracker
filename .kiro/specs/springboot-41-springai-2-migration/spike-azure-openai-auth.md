# Spike: Azure OpenAI auth + routing under `spring-ai-openai` (Spring AI 2.0 GA)

## Status

| Field | Value |
|---|---|
| **Gate for** | Task 8.1 (insight-service Azure adapter rewire) |
| **Does not block** | Task 4.1 / Wave 3 (`common-dto` Jackson 3) |
| **Owner** | _TBD_ |
| **Spike outcome** | **CLOSED** — Option C; `AzureOpenAiAuthConfig` bridges `DefaultAzureCredential` → `BearerTokenCredential`; `AzureOpenAiLiveSmokeTest` passed 2026-06-14 |

## Background

Waves 0–2 swapped `spring-ai-starter-model-azure-openai` for `spring-ai-starter-model-openai`
and bumped the BOM to Spring AI 2.0.0 GA. The [Spring AI 2.0 upgrade
notes](https://docs.spring.io/spring-ai/reference/2.0/upgrade-notes.html) confirm:

- `spring-ai-azure-openai` and its starter were **removed** in 2.0 GA.
- Users should migrate to `spring-ai-openai`, which is backed by the official **`openai-java`**
  SDK.
- Class names and configuration properties change (drop the `Azure` prefix; use
  `spring.ai.openai.*`).

The design (Step 2.1, Security) assumes **native Entra ID / Managed Identity** via
`spring.ai.openai.*` — no static OpenAI API key, and likely removal of
`com.azure:azure-identity`. Checkpoint 3 only proved compile + mock-profile boot; it did **not**
validate Azure wire format or MI auth against a real endpoint.

A temporary `spring.ai.openai.api-key=placeholder-key` default exists in `application.yml` so
OpenAI auto-configurations (e.g. audio speech) do not fail context init under the mock profile.
That placeholder must **not** reach production Azure calls.

## Questions to answer

### Question 1 — Authentication

Does `spring-ai-openai` (via `openai-java`) authenticate to an Azure OpenAI / Microsoft Foundry
endpoint using **DefaultAzureCredential / Entra ID / Managed Identity** without a static bearer
API key?

Document:

- Supported auth mechanisms exposed under `spring.ai.openai.*` (api-key, Azure AD token,
  workload identity, etc.).
- Whether Container Apps system-assigned MI (current Terraform path: *Cognitive Services OpenAI
  User* role) works without `com.azure:azure-identity` in application code.
- Whether a static `OPENAI_API_KEY` is required for any Azure/Foundry deployment shape we use.

**Current production intent** (`application-azure-ai.yml`, pre–Task 8.2):

- Profile: `azure-ai`
- Auth: Managed Identity via `DefaultAzureCredential` (M4 module); **no API key at runtime**
- Env: `AZURE_OPENAI_ENDPOINT`, `AZURE_OPENAI_DEPLOYMENT`

**Multi-cloud guardrail** (design Security, `tech.md`): prefer MI/Entra over static keys; do not
introduce a static OpenAI API key into Azure config.

### Question 2 — Endpoint routing / wire format

When `spring.ai.openai.base-url` (or equivalent) points at `https://*.openai.azure.com/` (or
Foundry), does the consolidated module produce the correct Azure request shape?

Azure differs from native OpenAI on the wire:

| Concern | Azure expectation |
|---|---|
| Path | Deployment name in URL path (not OpenAI model id in body alone) |
| Query | `api-version=…` required |
| Auth header | Often `api-key: <key>` **or** `Authorization: Bearer <AAD token>` depending on auth mode |

Document:

- Required `spring.ai.openai.*` properties for Azure/Foundry (base URL, deployment/model,
  api-version, credential).
- Whether `spring.ai.model.chat=openai` (replacing legacy `azure-openai`) is correct for Azure
  deployments.
- Any gaps vs. the legacy `spring.ai.azure.openai.*` keys still in `application-azure-ai.yml`.

**Test gap today:** `ChatModelPrimarySelectionPropertyTest` only asserts the primary
`ChatModel` bean class name contains `"openai"`. That does not prove Azure routing — compile-only
checkpoints cannot surface 401/404.

## Method

### Prerequisites

- Non-prod Azure OpenAI or Foundry resource with a deployed model (e.g. `gpt-4o-mini`).
- Container Apps MI **or** local Azure CLI login (`az login`) for Entra path; optional API-key
  path for comparison only.
- `insight-service` built on the migration branch with `spring-ai-starter-model-openai`.

### Procedure

1. **Configure azure-ai profile** with candidate `spring.ai.openai.*` keys (per Spring AI 2.0
   docs — use flattened keys where documented; defer full yml migration to Task 8.2).
2. **Disable static key** for the Entra/MI attempt: unset `OPENAI_API_KEY` / `spring.ai.openai.api-key`.
3. **Opt-in smoke call** (one of):
   - `SPRING_PROFILES_ACTIVE=local,azure-ai ./gradlew :insight-service:bootRun` then hit a
     sentiment/advisor endpoint, **or**
   - Minimal `@SpringBootTest` / manual `ChatClient` call under `azure-ai` profile (record in
     this doc; do not commit secrets).
4. **Capture evidence:**
   - HTTP method, URL path, query params (`api-version`), auth header type (redact token values).
   - Response status (200 vs 401/404) and error body summary.
5. **For Q1 specifically:** check whether `spring-ai-openai` / `openai-java` exposes a **dynamic
   API-key or credentials supplier** (or custom-header hook). If yes, that is the likely Option C
   mechanism — feed a `DefaultAzureCredential` AAD bearer token as a rotating credential rather
   than a static secret.
6. **Repeat** with API-key auth only if MI fails — to isolate routing vs auth failures.

### Optional tooling

- Enable `logging.level.org.springframework.ai=DEBUG` and relevant `openai-java` HTTP logging
  (if available) for one request.
- `curl -v` against the same endpoint with known-good MI token from `az account get-access-token`
  to compare wire shape.

## Findings (record here after spike)

### Auth (Question 1)

| Mechanism | Supported? | Property / config | Notes |
|---|---|---|---|
| Managed Identity / DefaultAzureCredential | **Yes (via bridge)** | `AzureOpenAiAuthConfig` sets `Credential` on `OpenAi*Properties` | Spring AI's built-in `AzureInternalOpenAiHelper` path failed at runtime (`NoClassDefFoundError` in test JVM); explicit bridge required |
| Entra ID (user/delegated via `az login`) | **Yes** | same bridge | Local smoke: `DefaultAzureCredential` fell through MI → **AzureCliCredential** after IMDS unreachable |
| Static `api-key` | **Yes** | `OPENAI_API_KEY` / `spring.ai.openai.api-key` | Comparison path only; skipped when env unset |
| Requires `com.azure:azure-identity` in app | **Yes** | `insight-service/build.gradle` | Required for `DefaultAzureCredentialBuilder`; keep dependency |

**Request auth header observed:** `Authorization: Bearer <redacted>` (not `api-key`)

**Root cause of initial 401s:** `api-key: ${OPENAI_API_KEY:}` binds to empty string when unset; `OpenAiSetup` treats `""` as deliberate no-auth (strips `Authorization`). Placeholder `placeholder-key` from `application.yml` also leaked before profile override.

### Routing (Question 2)

| Check | Pass? | Notes |
|---|---|---|
| Deployment in URL path | **Yes** | `/openai/deployments/gpt-4o-mini/chat/completions` (confirmed via `az rest`) |
| `api-version` query param present | **Yes** | `api-version=2024-10-21` on `az rest`; openai-java default for Foundry |
| Base URL `*.openai.azure.com` accepted | **Yes** | Auto-detected as `MICROSOFT_FOUNDRY` |
| Chat completion returns 200 | **Yes** | `az rest` → `"OK"`; `AzureOpenAiLiveSmokeTest` → structured `AnalysisResult` |

**Sample request shape (redacted):**
```
POST https://wealth-prod-aoai-ff267.openai.azure.com/openai/deployments/gpt-4o-mini/chat/completions?api-version=2024-10-21
Authorization: Bearer <redacted>
Content-Type: application/json
{"messages":[{"role":"user","content":"..."}],"max_tokens":500,"temperature":0.2}
```

## Decision (fill after spike)

Choose **one** path and record rationale:

### Option A — Native Entra/MI supported

- Task 8.2 migrates `application-azure-ai.yml` to `spring.ai.openai.*` with **MI/Entra only**;
  remove placeholder `api-key` from production profile overrides.
- Task 8.6: remove `com.azure:azure-identity` if no other code path needs it.
- Update design Step 2.1 Security: confirm "no custom AAD bridge" holds.
- Add opt-in integration/smoke test for `azure-ai` profile (Task 8.7+ or migration gate).

### Option B — Native Entra/MI **not** supported; API key only

- **Conflict** with design Security and multi-cloud guardrail — escalate before Task 8.1.
- Options: custom credential supplier bridging `DefaultAzureCredential` → `openai-java` client,
  stay on a supported auth path, or revisit adapter choice.
- Do **not** ship static keys to Container Apps prod without explicit architect approval.

### Option C — Partial (routing works, MI requires bridge)

- Document minimal bridge (e.g. token supplier bean) in Task 8.1 scope.
- **Likely middle path:** if the module exposes a dynamic API-key/credentials supplier, wire
  `DefaultAzureCredential.getToken(...)` to supply a rotating AAD bearer token (not a static
  secret) — verify during the spike before building a custom `RestClient` interceptor.
- Keep or narrow `azure-identity` dependency with documented justification.

**Selected option:** **Option C** — routing works natively; passwordless auth requires minimal bridge (`AzureOpenAiAuthConfig`) wiring `DefaultAzureCredential` → `BearerTokenCredential` on Spring AI connection properties. Option A (zero-bridge native autoconfig) does **not** hold: empty `api-key` triggers no-auth mode; Spring AI's internal `AzureInternalOpenAiHelper` path did not load reliably in our JVM.

**Architect sign-off:** Wire smoke passed (`AzureOpenAiLiveSmokeTest`, 2026-06-14); `az rest` Bearer token control returned 200.

## Downstream updates (after decision)

| Artifact | Action |
|---|---|
| `tasks.md` Task 8.1 / 8.2 | Align acceptance criteria with decision |
| `design.md` Step 2.1, Security | Reconcile F4 / "no custom AAD bridge" if Option B or C |
| `application-azure-ai.yml` | Rewrite in Task 8.2 (flattened keys; no static prod key if Option A) |
| `application.yml` placeholder | Ensure azure-ai profile fully overrides placeholder key |
| Tests | `AzureOpenAiLiveSmokeTest` (opt-in); record findings here on pass |

## Deferred (not in this spike)

- **#4 Jackson 2 pin scope/version:** Wave 7 classpath audit — narrow `resolutionStrategy` to
  `insight-service`, pin to `openai-java`'s declared Jackson 2 version, CI audit for Property 12.
- **#5 Flattened config keys:** Task 8.2/8.3 — `spring.ai.openai.chat.model` /
  `spring.ai.openai.chat.temperature` instead of deprecated `.options.*` variants.

## References

- [Spring AI 2.0 upgrade notes](https://docs.spring.io/spring-ai/reference/2.0/upgrade-notes.html) — azure-openai module removal, openai-java
- [Spring AI 2.0 OpenAI Chat](https://docs.spring.io/spring-ai/reference/2.0/api/chat/openai-chat.html)
- Design: `.kiro/specs/springboot-41-springai-2-migration/design.md` — Step 2.1, Security
- Current Azure profile: `insight-service/src/main/resources/application-azure-ai.yml`
