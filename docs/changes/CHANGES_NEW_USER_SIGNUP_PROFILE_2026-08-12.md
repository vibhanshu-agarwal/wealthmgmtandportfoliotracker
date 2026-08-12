# Changes Summary — New User Signup & Profile (Self-Service Auth)

**Date:** 2026-08-12
**Spec:** `.kiro/specs/new-user-signup-profile/` (requirements.md, design.md, tasks.md)
**Plan:** `docs/superpowers/plans/2026-08-11-new-user-signup-profile.md` (8 tasks, executed via
subagent-driven development)
**Branches:** `feat/new-user-signup-profile` (PR [#85](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/85)),
`fix/gateway-auth-filter-cleanup` (PR [#88](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/88))
**Preceding changelog:** `docs/changes/CHANGES_PRODUCTION_RATE_LIMITING_2026-07-05.md`
**Scope:** `api-gateway` (new `com.wealth.gateway.auth` package + gateway filters),
`portfolio-service` (schema migrations V14–V16), `frontend` (signup page, Better Auth removal),
plus Terraform/infrastructure and a live-production fix.

---

## Summary

Replaced the placeholder single-hardcoded-credential login (`app.auth.email` /
`app.auth.password`) with real, per-user authentication: a `user_credentials` table in
Postgres, bcrypt password hashing, a self-service `/api/auth/signup` endpoint, and a
read-only demo account for recruiter-facing showcase access. This also retired the
project's earlier, never-fully-wired Better Auth integration on the frontend.

The work went through four phases, in order: (1) the 8-task feature build, (2) CI
stabilization that surfaced and fixed a real reactive-pipeline bug affecting every proxied
request, (3) requested follow-up hardening after merge, and (4) a live-production incident
discovered during post-merge verification, root-caused and fixed the same day.

---

## Phase 1 — Feature Delivery (PR #85)

### Database — `portfolio-service` migrations V14–V16

- **V14** (`Add_User_Credentials_And_Account_Flags.sql`): new `user_credentials` table
  (`user_id` FK, `email`, `password_hash`); `users.name` and `users.read_only` columns added.
- **V15** (`Reconcile_Auth_Seed_Users.sql`): idempotently seeds three accounts —
  demo/recruiter (`demo@wealthtracker.dev`, read-only, password intentionally public and
  wired to `NEXT_PUBLIC_DEMO_PASSWORD` for the login pre-fill), dev (`dev@local`, writable),
  and the E2E test user — all with fresh bcrypt(cost=12) hashes. Also reassigns the seeded
  showcase portfolio (AAPL/TSLA/BTC) from the dev user to the demo account, so the
  read-only recruiter login lands on populated data instead of an empty dashboard.
- **V16** (`Drop_Better_Auth_Tables.sql`): drops the `ba_*` tables from the retired Better
  Auth integration.

### Gateway auth — new `com.wealth.gateway.auth` package

- `SignupValidator`, `UserCredentialRepository`, `PasswordHasherConfig`,
  `AuthenticationService`, `SignupService` plus typed exceptions
  (`InvalidCredentialsException`, `DuplicateEmailException`, `CredentialStoreUnavailableException`,
  `ProvisioningFailedException`, `ValidationException`).
- `AuthenticationService.authenticate()` burns equivalent CPU against a fixed dummy bcrypt
  hash on both "unknown email" and "malformed stored hash" paths, so no login-failure
  branch is distinguishable by timing (Req 3.4).
- `GatewayAuthDataConfig` — real, DB-backed beans, gated on `spring.datasource.url` being
  present (`local`/`aws`/`azure` with a datasource configured).
- `GatewayAuthFallbackAutoConfiguration` — registered as a genuine `@AutoConfiguration` so
  its `@ConditionalOnMissingBean` beans are evaluated strictly after `GatewayAuthDataConfig`'s
  component-scanned beans. Without this, any profile lacking a datasource would fail to
  boot the *entire* gateway (not just auth) at context refresh. Instead, login/signup fail
  closed with a 503. (This exact fallback path is what a live infrastructure gap exercised
  in Phase 4 below.)
- `ApiGatewayApplication` excludes `DataSourceAutoConfiguration` /
  `DataSourceTransactionManagerAutoConfiguration` / `JdbcTemplateAutoConfiguration` so the
  datasource stays entirely opt-in per profile.
- `AuthController` rewritten: byte-identical uniform 401 for every login-failure reason
  (unknown email, wrong password, blank fields, malformed stored hash — Req 3.5/3.6/10.6),
  defensive `Throwable` catch-alls on both `login()` and `signup()`.
- `ReadOnlyEnforcementFilter` — blocks portfolio/market writes from the read-only demo
  account, with an AI-route allowlist (`/api/chat/**`, `/api/insights/generate/**`).
- `AuthRateLimitFilter` — throttles `/api/auth/login` and `/api/auth/signup` via a shared
  `Auth_Bucket`, since `/api/auth/**` is a controller endpoint, not a proxied route.
  Requires `@Qualifier("authRateLimiter")` — an unqualified parameter would silently
  resolve to `standardRateLimiter` (the `@Primary` bean) and enforce 10x-more-permissive
  limits while `Retry-After` still looked correct.

### Frontend

- New signup page (`/signup`), client-side validator, login↔signup navigation.
- Better Auth fully retired: dead code, the dependency, an orphaned chat Server Action, and
  an orphaned dev-seed script all removed.

### Testing

- Testcontainers (Postgres + Redis) integration suite and ArchUnit guardrails (Task 8).
- Req 10.3 provisioning-rollback test: a demo-account write attempt is rejected *and*
  verified to leave the database completely unchanged.

### Final whole-branch review — 2 Critical + 3 Important findings, all fixed

- **C1:** `infrastructure/terraform/azure/main.tf` and
  `infrastructure/terraform/aws/modules/compute/main.tf` were missing
  `SPRING_DATASOURCE_URL`/`USERNAME`/`PASSWORD` injection into `api-gateway`, unlike
  `portfolio-service`'s existing pattern. Fixed in source. *(See Phase 4 — this fix was
  merged but not deployed until the incident below.)*
- **C2:** prod health probes needed `management.health.db.enabled: false` on
  `application-aws.yml`/`application-azure.yml` (api-gateway now holds a live `DataSource`
  bean; a Postgres connection over the internet from a scale-from-zero instance has the same
  DNS/connect-stall risk as the existing Redis health-check exclusion).
- **I1:** `deploy-azure.yml`'s `NEXT_PUBLIC_DEMO_EMAIL`/`PASSWORD` were pulled from
  `secrets.E2E_TEST_USER_EMAIL`/`PASSWORD` — wrong account entirely. Changed to the literal
  demo credentials.
- **I2/I3:** additional health-probe and fallback-autoconfiguration test coverage.

---

## Phase 2 — CI Stabilization (still PR #85)

Two CI jobs went red after the initial push. Both were root-caused, not papered over.

### `RateLimitingIntegrationTest.requestsExceedingBurstAreThrottled` — issue [#86](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/issues/86) (closed)

Deterministic failure, not flaky: every one of 38 consecutive requests proxied through to a
real `Connection refused` against the intentionally-nonexistent downstream, meaning the
`local` profile's implicit `RedisRateLimiter` never returned 429. Confirmed pre-existing
(reproduces on `main`, unrelated to this branch). Per an explicit time-boxed investigation,
three fix attempts were tried (retry-loop hardening, `spring.data.redis.url` property
format, an explicit `localRateLimiter` bean matching prod's pattern); none resolved it and
the last one regressed a sibling test, so it was reverted. Test marked `@Disabled`
referencing the issue — **superseded by the root-cause fix found while investigating #87,
below.**

### "aborted" client-side errors on proxied GET requests — issue [#87](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/issues/87) (closed)

Initially scoped as one Playwright sub-test failing with a client-side `aborted` error on
`GET /api/portfolio` despite the server logging `200 OK`. A second CI run then showed the
same symptom across 6 tests total once the (unrelated) credential-fixing commit stopped
masking it by failing earlier in the same spec. Given the widened scope, **investigation was
escalated to the user rather than unilaterally disabling more tests** — the explicit
decision was "investigate properly," not "disable and log."

**Root cause, found via `curl -v --http1.1`:** the server sends `HTTP/1.1 200 OK` with
`Transfer-Encoding: chunked`, begins the body, then the connection drops *without the
terminating `0\r\n\r\n` chunk* — reproducible with plain `curl`, proving it wasn't a
Playwright/Node quirk. Traced to `ReadOnlyEnforcementFilter`:

```java
// Before — bug
return exchange.getPrincipal()
    .flatMap(principal -> ... return chain.filter(exchange); ...)
    .switchIfEmpty(chain.filter(exchange));
```

`chain.filter(exchange)` returns `Mono<Void>`, which *always* completes empty — so
`switchIfEmpty` re-subscribed the entire downstream chain a second time on **every
successful request**, proxying the response twice and mutating already-committed response
headers on the second pass, which truncated the HTTP/1.1 chunked stream before its
terminating chunk. This also meant a blocked demo-account write returned 403 to the client
**while the write was still forwarded downstream** — a second, more severe defect hiding
behind the same bug, missed by the existing test because that test context has no
reachable downstream service, so the leaked call died silently on connection-refused.

Fixed by resolving the branch to a value (`.defaultIfEmpty(Boolean.FALSE)`) before a single
terminal `flatMap`, so the chain is subscribed exactly once regardless of outcome.
`ReadOnlyEnforcementFilterChainTest` (new) asserts subscription count via
`Mono.doOnSubscribe`. **This was the actual root cause of issue #86 too** — the same
double-subscription re-ran the rate limiter's Redis lookup on a committed response and
threw, which is why the local-profile limiter never returned 429. Both tests disabled for
these two issues were re-enabled (unrelated pre-existing skips elsewhere in the E2E suite —
`dashboard-data.spec.ts`, `live-contract.spec.ts`, `mocked-chaos.spec.ts` — predate this
branch and were out of scope).

Commit: `e39f2c8` — *stop ReadOnlyEnforcementFilter re-subscribing the filter chain*.

---

## Phase 3 — Follow-up Hardening (PR #88)

Requested explicitly after PR #85 merged, time-boxed per item.

1. **`JwtAuthenticationFilter` — same defect class as `e39f2c8`.** `switchIfEmpty` was
   composed directly around a branch returning `chain.filter(...)`. Verified live against
   the (then-)running gateway: a single successful `GET /api/portfolio` logged both `JWT
   validated for sub=...` *and* `No principal found on exchange — rejecting request`, and
   re-issued `setStatusCode(401)`/`setComplete()` after the response may already be
   committed. Harmless in that specific case only because the response was already
   committed by then, but a latent spurious-401 risk for any route completing without
   committing. Fixed with the same `.map()`-before-`switchIfEmpty` pattern (see design note
   in `JwtAuthenticationFilter.java`); new `JwtAuthenticationFilterChainTest` (5 cases)
   fails pre-fix, passes post-fix.
2. **Orphaned Playwright scripts.** `fix-verification.test.ts`,
   `global-setup-entrypoint.test.ts`, `global-setup-export.test.ts` — one-off `ts-node`
   verification scripts from the already-shipped `azure-demo-readiness-phase1` spec, no
   `test()`/`describe()` calls, meant to be run directly per their own header comments.
   Their `.test.ts` naming matched Playwright's default `testMatch`, so every CI run's file
   collection silently executed their side-effecting code — spawning real child processes,
   adding ~15s, and reporting one silently-failing assertion nobody saw. Excluded via
   `testIgnore` (collection time 15s → 6s; verified the real 18-test/8-file suite is
   unaffected).
3. **`ci-better-auth-postgres` backlog item closed as superseded.** Its fix plan targeted
   Better Auth, which this feature retired outright (V16 + Task 7). Also removed the
   vestigial `BETTER_AUTH_SECRET`/`BETTER_AUTH_URL`/`DATABASE_URL` env vars from
   `frontend-ci.yml`'s `build-and-test` job, proven unread by any code.
4. **Stale javadoc.** `GatewayRateLimitConfig`'s class header still said "two named beans"
   after Task 4 added a third (`authRateLimiter`); updated.

---

## Phase 4 — Production Incident: Signup Non-Functional on Live Site

**Discovered:** during post-merge live verification of `https://vibhanshu-ai-portfolio.dev/`,
requested explicitly after both PRs merged.
**Fixed:** same day, `terraform apply` via `workflow_dispatch` on
`.github/workflows/terraform-azure.yml`.

### Root cause

Final review finding **C1** (see Phase 1) — injecting `SPRING_DATASOURCE_URL`/`USERNAME`/
`PASSWORD` into api-gateway's Azure Container App, mirroring portfolio-service's existing
pattern — was written into `infrastructure/terraform/azure/main.tf` and merged, but **never
applied to live infrastructure.** `terraform-azure.yml` only runs `terraform apply` on a
manual `workflow_dispatch`; it never runs automatically on merge (by design — `plan` runs on
every PR as validation only). Confirmed directly: `az containerapp show` listed api-gateway's
live env vars and none of the three datasource variables were present.

### Consequences, all traced with direct evidence (Azure CLI + Log Analytics)

1. **Crash-loop on every deploy.** `application-prod.yml` declares
   `spring.datasource.url: ${SPRING_DATASOURCE_URL}` with no default. With the variable
   absent, `GatewayAuthDataConfig`'s `@ConditionalOnProperty` evaluation threw
   `PlaceholderResolutionException` instead of cleanly evaluating false. Log Analytics
   showed 5–7 failed context-refresh attempts per deploy before the process recovered
   within the same boot.
2. **`/api/auth/signup` vanished from the route table.** Production's
   `/actuator/mappings` showed only `POST /api/auth/login` registered — `/api/auth/signup`
   was completely absent, so every signup attempt returned a raw framework 404. Proved this
   was a side effect of the crash-loop recovery path, not a code bug: built the identical
   Docker image fresh from `main` and ran it with a resolvable (dummy) datasource URL — it
   booted cleanly in 7s and registered both routes correctly.
3. **Login silently used the fallback path.** With no real datasource,
   `GatewayAuthFallbackAutoConfiguration` was active — exactly the fallback design described
   in Phase 1, exercised here by the infrastructure gap it was built to handle. Login didn't
   crash, but no credential — including correctly-seeded ones — could ever succeed.

### Fix

Triggered `Terraform Azure Infrastructure` (`workflow_dispatch`, `action=apply`) to apply
the already-merged, already-reviewed Terraform change. This provisioned the three datasource
variables as Container App secrets and rolled a new `api-gateway` revision.

### Live verification (post-fix)

- New revision booted cleanly in 25s — zero crash-loop log entries.
- `/actuator/mappings` — both `POST /api/auth/login` and `POST /api/auth/signup` registered.
- **Demo login** (API): 200, valid JWT. **Through the real UI**, pre-filled demo credentials
  submitted → redirected to `/overview` → real showcase portfolio rendered
  ($56,915.64; AAPL/TSLA/BTC), no console errors.
- **Fresh signup** (API): 201, valid JWT, new `userId` assigned; authenticated
  `GET /api/portfolio` → 200, `[]` (correct for a brand-new account). **Through the real
  UI**, filled and submitted the actual signup form (React-controlled inputs, native-setter
  + `input` event dispatch to bypass automation limitations) → real API call → session
  written to `localStorage` → redirected to `/overview` → correct empty-state dashboard, no
  console errors.
- **Dev user login** (`dev@local`, the other seeded, non-demo account): 200, JWT `ro`
  claim `false` (correctly writable, unlike demo's `ro:true`). Portfolio reads 200/`[]` —
  expected, since V15 reassigned the original showcase portfolio to the demo account.
  Verified both via direct API and through the real UI. Write-path testing against this
  account was deliberately **not** performed live to avoid leaving persistent test data in
  production; the local Docker stack already covers it.

### Notes for future incidents

- `portfolio-service` scales to zero on Azure's consumption tier — the first request after
  idle has a genuine cold-start delay (observed ~35s). Expected infrastructure behavior, not
  a regression.
- `terraform-azure.yml`'s plan-only-on-PR / apply-only-on-manual-dispatch split means a
  Terraform-side fix landing in a feature PR is **not live until someone manually runs
  apply.** No process change made yet — flagged as a gap below.

---

## Tests Run

| Suite | Result |
|---|---|
| `./gradlew :api-gateway:test` (incl. new `JwtAuthenticationFilterChainTest`, `ReadOnlyEnforcementFilterChainTest`) | ✅ BUILD SUCCESSFUL |
| `./gradlew :api-gateway:integrationTest` (145 tests, Testcontainers Postgres+Redis) | ✅ pass |
| `npx vitest run` (frontend) | ✅ 199/199 pass across 25 files |
| `npx tsc --noEmit` / `eslint .` (frontend) | ✅ no errors |
| `npx playwright test` (`docker-build-verify`, full Docker Compose stack, 18 tests / 8 spec files) | ✅ pass |
| CI Verification Pipeline (both PRs, final pushes) | ✅ all jobs green (unit, integration, pact-consumer, docker-build-verify) |
| Live production verification (post-Terraform-apply) | ✅ signup, demo login, dev login all confirmed end-to-end via direct API and real browser UI |

---

## Known Gaps / Follow-ups

Logged as backlog items rather than tracked here — this changelog is a record of what
changed, not where open work lives:

- **No automatic `terraform apply` on merge for Azure infra** — the process gap that
  actually caused Phase 4's incident, and can recur for any future Terraform PR.
  `docs/todos/backlog/terraform-apply-not-automatic-on-merge/README.md`
- **`application-prod.yml` datasource properties have no `:` default** (why the missing env
  var became a crash-loop instead of a clean fallback) and
  **`RateLimitingIntegrationTest.requestsExceedingBurstAreThrottled`'s timing-sensitive
  assertion window** — both logged in `docs/todos/TODOS_2026-04-07.md`.
