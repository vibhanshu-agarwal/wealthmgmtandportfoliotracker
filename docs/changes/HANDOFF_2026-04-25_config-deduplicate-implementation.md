# Handoff — Config Deduplication Implementation (2026-04-25)

## Repo / Branch State

- **Repo**: `wealthmgmtandportfoliotracker`
- **Working branch**: `config/deduplicate-config` (current HEAD: `a392d55`)
- **PR**: [#10 — refactor: config dedup + migrate insight-service to Bedrock Claude Haiku 4.5](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/10) → `main`
- **PR status at handoff**: Owner is **merging into `main`**. After merge, work continues on a fresh branch off `main`.

### CI status on PR #10 head (`a392d55`)
- 12/13 checks pass; `Qodana` neutral (4 minor warnings).
- 1 failure: `docker-build-verify` (Playwright report artifact missing, exit code 1). Owner has decided this is **not directly correlated** with the Bedrock migration and is proceeding with the merge.

## Authoritative documents

1. `docs/audit/2026-04-23-config-duplication-audit.md` — **execution plan**. Contains 11-item ordered sequence, blast-radius analysis, and review checklist. This is the source of truth for the dedup work.
2. `docs/changes/HANDOFF_2026-04-25_config-deduplicate-config.md` — earlier handoff snapshot (pre-PR-10 state); kept for traceability only.

## Where we are in the 11-item sequence

**Nothing implemented yet.** All work below is queued. Recommended order (from audit §"Recommended sequence"):

| # | Item | Scope | Risk |
|---|---|---|---|
| 1 | Fix `SPRING_MONGODB_URI` → `SPRING_DATA_MONGODB_URI` in Compose (audit 1.3) | 1 line | Low — behavior change |
| 2 | Delete dead `API_PROXY_TARGET` from `frontend-e2e-integration.yml` (2.2 first step) | 1 line | Trivial |
| 3 | Delete dead `lambda_java_runtime` variable (3.1a) | 1 block | Trivial |
| 4 | Delete unused `AUTH_SECRET` / `NEXTAUTH_SECRET` aliases (2.3) | 2 workflow files | Trivial |
| 5 | Delete dead `lambda_adapter_layer_arn` (3.1b) | 1 var + 2 tfvars | Trivial |
| 6 | Terraform root↔compute dedup (1.1) + dead `s3_key_api_gateway` (3.1c) | Multi-file, must land together | Low |
| 7 | api-gateway route URL defaults → `app.routes.*` (2.1) | 1 file | Low |
| 8 | Unify frontend gateway URL env var (2.2 remainder) | 6 tests + 2 prod CI + 1 README | Medium |
| 9 | Extract `spring.kafka.*` prod block via `spring.config.import` (1.2) | 3 prod YAMLs + new shared fragment | Low |
| 10 | YAML anchors in `docker-compose.yml` (1.4) | 1 file | Trivial / cosmetic |
| 11 | Remove `@Value` defaults in `AuthController` (2.4) | 1 Java file | **Behavioral** — fail-fast at startup |

**Suggested first move for the next agent:** start a new branch off freshly merged `main`, then ship Item 1 (Mongo URI fix) as its own PR.

## Things that changed in PR #10 — must be reflected before implementing

The audit was written against pre-PR-10 line numbers. After merge, **before touching code**, update the audit doc with the following corrections (lines verified at HEAD `a392d55`):

### Line-number drift in `docker-compose.yml`
| Audit reference | Old line(s) | New line(s) |
|---|---|---|
| `SPRING_MONGODB_URI` typo (1.3) | 103 | **125** |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` × 3 (1.4) | 78, 104, 135 | **100, 126, 164** |
| `SPRING_DATA_REDIS_HOST/PORT` (1.4) | 136, 172 | **165, 207** |
| `INTERNAL_API_KEY` × 4 (1.4) | 79, 105, 139, 177 | **101, 127, 168, 212** |
| `APP_AUTH_USER_ID` (2.4) | 170 | **205** |
| `PORTFOLIO_SERVICE_URL` × 2 (note under 2.1) | 138, 174 | **167, 209** |

### `INTERNAL_API_KEY` placeholder name changed
PR #10 standardized the compose env to `INTERNAL_API_KEY: ${TF_VAR_internal_api_key}` (was `${INTERNAL_API_KEY}`). Update the YAML-anchor proposal in audit §1.4 accordingly.

### New (intentional) duplication to leave alone
`us-east-1` appears as a Bedrock region default in **two** files:
- `insight-service/src/main/resources/application-aws.yml` line 21 (hard-pin on Lambda)
- `insight-service/src/main/resources/application-bedrock.yml` line 21 (`${AWS_REGION:us-east-1}`, default for local opt-in smoke testing)

This is **intentional layering** (`aws.yml` is loaded after `bedrock.yml` and overrides). Add a one-line note to the audit's "Explicitly out of scope" section so a future reader does not try to dedupe it.

## Pending issues to fold into testing

- **Portfolio service has a failing issue** (specifics not yet investigated by user request). Address opportunistically during testing of audit items that touch portfolio-service (most relevant: items 6, 9, 10). Do not investigate proactively — fix as encountered.
- **`docker-build-verify` job** on the merged commits — keep an eye on whether it's still failing on `main` after merge. If it persists, surface to user; do not silently fix.

## Pinned constraints / preferences (carry forward)

- User prefers each audit item shipped as **its own PR**, not batched.
- Item 11 (`@Value` default removal in `AuthController`) is a **deliberate** behavioral change toward fail-fast startup. Confirm before merging that PR.
- Item 8 (frontend gateway URL rename) touches **live prod monitoring workflows** — land with intent.
- Item 6 must land as a **single coordinated PR** — `terraform plan` will fail if variable declarations are dropped without removing the matching `-var=` flags from CI/scripts.
- Repo is **Spring Boot 4 + Gradle multi-module**. `spring.config.import` is supported natively (no dedicated `common-config` module needed).
- All three services already depend on `common-dto` (verified). The shared Kafka YAML fragment for item 9 lives at `common-dto/src/main/resources/config/application-prod-kafka.yml`.

## Out-of-scope reminders

- Shared Kafka `@Configuration` / Boot 4 auto-configuration module — bigger refactor, **not** part of this dedup pass.
- YAML↔Java overlap in `PortfolioKafkaConfig` / `InsightKafkaConfig` — belongs with the auto-config refactor above.
- Port-number harmonization — too diffuse to dedupe cleanly here.

## First actions for the next agent

1. `git fetch origin && git checkout main && git pull` (PR #10 should be merged by the time the next session starts).
2. Verify `docs/audit/2026-04-23-config-duplication-audit.md` is on `main`.
3. Apply the line-number corrections + the `${TF_VAR_internal_api_key}` rename + the Bedrock-region intentional-layering note to the audit doc as a single small "doc refresh" commit (or fold into the Item 1 PR).
4. Cut a feature branch from `main` (suggested: `config/dedup-01-mongo-uri-fix`) and ship Item 1.
