# Cursor Kickoff — Spec B1 Wave 2: gateway provisioning + asset route

> **SUPERSEDED STATUS NOTICE — 2026-08-24:** This kickoff preserves the constraints and rationale
> used to implement draft PR #131, but its branch, migration, checkpoint, line-number, and blocker
> statements describe the 2026-08-21 baseline and are no longer current. In particular, Spec A
> V17–V19 are merged and applied, checkpoints 9.6–9.10 are complete, and tasks 2.1/2.3 are already
> implemented but remain unmerged in draft PR #131. **Do not execute this document as a current
> kickoff.** Read [`docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md`](../plans/ASSET_PICKER_E2E_MASTER_PLAN.md)
> and [B1 `tasks.md`](../../.kiro/specs/portfolio-composition-contract/tasks.md) for current status.
> A new self-contained Cursor handoff, anchored to the master-plan PR's merge SHA, will supersede
> this document operationally before implementation resumes.

**Date:** 2026-08-21
**Prepared for:** Cursor (implementation)
**Baseline:** `origin/main`, currently `09f6a1f` or later — Wave 1 (legacy writer retirement, R-0) is merged, deployed, and kept (STOP/GO recorded GO)
**Suggested branch:** `feat/b1-wave-2-gateway-provisioning` off `main`
**Spec:** `.kiro/specs/portfolio-composition-contract/` — read from `main`, which is authoritative

---

## 0. What this wave is, and what it is not

**Only two of six tasks are actionable right now. The other four are blocked on a different spec's unexecuted production checkpoints — not on anything in this repo's `main` branch history.**

Task 2.2 (G1's dual-schema candidate proof) requires a database migrated to **V19** — "Spec A's final migration, and this graph's predecessor" per the design. Checked directly, not assumed: `main` has migrations only through **`V16`**. `V17`–`V19` exist only on `feat/supported-asset-postgres-repair` — checked by diffing both held Spec A branches directly, not assumed from one label covering both: `feat/supported-asset-mongo-repair` also tops out at `V16` and carries no new Flyway migrations at all, only the Mongo-side repair Job code. Both branches are **deliberately unmerged** — Spec A's own changelog states "Both branches are complete with green test matrices; neither has a PR, deliberately." They correspond to Spec A's tasks **9.6** (Postgres repair — the one that actually produces `V17`–`V19`) and **9.7** (Mongo repair — a separate irreversible checkpoint with no Flyway migration of its own, but still part of what design.md means by Spec A's "production completion"), both marked `CHECKPOINT — IRREVERSIBLE` in `.kiro/specs/supported-asset-integrity/tasks.md`, and both still unchecked — confirmed by reading that file directly, not inferred from "Spec A is merged." Design.md is explicit that this is not incidental: *"Spec A's production completion is therefore the predecessor of this whole release graph"* — not just of Wave 3's schema wave, of **this one too**. Task 2.2 specifically needs only 9.6/`feat/supported-asset-postgres-repair`; the broader release-graph gate on 2.4–2.6 needs both.

That is a different kind of blocker than Wave 1's merge/R-0 distinction. It is not something this wave's implementation can resolve by writing code, and it is not something you should route around by pulling Spec A's held branches onto this one as a side effect of Wave 2 — those branches were left unmerged on purpose, tied to a real production cutover procedure (verified backups, a terminal Mongo repair Job) that is the repo owner's call, not this kickoff's.

**What that means concretely:**

- **2.1** (`SignupService` provisioning insert) and **2.3** (`/api/assets/**` gateway route) have no *code* dependency on V17–V20 at all — implement and test both now, against the real `V16` schema already on `main`.
- **Implementing is not the same as merging.** `api-gateway/**` sits in `deploy.yml`'s `push: paths:` filter — merging fires an automatic production deploy, exactly like `portfolio-service/**` did for Wave 1. Task 2.4 explicitly gates that deploy on 2.2 being green ("STOP/GO — G1 before deploy. Go: 2.2 green"). Since 2.2 is blocked (see above), **open the PR for 2.1+2.3 but do not merge it** — mark it blocked, and hold it open until 2.2 and 2.4 both clear. See §6.
- **2.2** (G1 dual-schema proof), **2.4** (STOP/GO before deploy), **2.5** (G2 serving proof), and **2.6** (STOP/GO — R-A) all sit behind V19 existing on this branch. Do not attempt them. Raise the Spec A dependency to the owner instead of working around it.

**Backend + gateway — and signup's behavior genuinely changes, not "inert."** `POST /api/auth/signup` is a public, unauthenticated endpoint (`AuthController.java:54-56`, `permitAll` at the gateway per `SecurityConfig.java:34`), linked from the live frontend (`frontend/src/app/(auth)/signup/page.tsx`), and today it creates a `users` row and a `user_credentials` row with **no portfolio** — confirmed by `AuthIntegrationTest.signupProvisionsUsersAndUserCredentialsRows` (`AuthIntegrationTest.java:119-137`), which currently asserts only those two tables. After 2.1 ships and merges, **every successful signup gains an additional database write and a new failure mode**: if the portfolio insert fails, the whole signup now rolls back and returns `503` where it would have succeeded today (that `503`, not a `409`, is exactly why §1.1 places the insert outside the email-specific `catch`). Requirement 1.13 ("no product path creates a second portfolio") is about *multi*-portfolio creation staying unreachable — it says nothing about signup itself, which is reachable right now and stays reachable. Don't conflate the two. The `/api/assets` route (2.3) *is* inert in the sense that matters for this wave — it proxies to a controller that doesn't exist yet (task 4.11) — but 2.1 is a real, live behavior change once it's actually deployed, which is exactly why §6's merge hold matters.

**Scope: tasks 2.1 and 2.3 only, for now.** Not 2.2/2.4/2.5/2.6 (blocked, see above), not Wave 3, not the `GET /api/assets` controller itself (task 4.11).

## 1. Task 2.1 — `SignupService` provisioning insert

_Requirements: 1.5, 1.6, 1.7, 5.16_

### 1.1 What and where

`api-gateway/src/main/java/com/wealth/gateway/auth/SignupService.java` — `provision()` (lines 36–67) already runs `credentialRepository.insertUser(...)` then `credentialRepository.insertCredential(...)` inside a `try { ... } catch (DuplicateKeyException dup) { status.setRollbackOnly(); throw new DuplicateEmailException(); }` block (lines 44–50), all inside the outer `transactionTemplate.execute(...)` callback.

**Add `insertPortfolio(...)` after that `try/catch` closes, not inside it** — between line 50's closing brace and the `String token;` line that follows, still inside the same `transactionTemplate.execute(...)` callback. Putting it inside the email-specific `try` would mis-map any failure there: a `DuplicateKeyException` from the portfolio insert would be caught by the existing `catch` and rethrown as `DuplicateEmailException` → `409`, which is wrong — it isn't an email duplicate. Placed after that block instead, any exception from `insertPortfolio` propagates uncaught out of the `transactionTemplate.execute` callback. That's sufficient on its own for the transaction to roll back — `TransactionTemplate` rolls back automatically on any exception thrown from its callback, `setRollbackOnly()` isn't required for that part — and the exception then reaches `.onErrorMap` (line 62-66), which wraps anything that isn't already `ValidationException`/`DuplicateEmailException`/`ProvisioningFailedException` into `ProvisioningFailedException` → `503`. That's the correct, specified failure mode for a portfolio-provisioning failure (Requirement 1.6: user creation and portfolio provisioning commit or fail together — a `503`, not a `409` that implies the wrong cause).

### 1.2 The exact statement, and why it's written this way

Requirement 1.7 / D2 / D17 already settled that `api-gateway` is allowed to write a table `portfolio-service` owns — don't re-litigate that. The statement itself:

```sql
INSERT INTO portfolios (id, user_id) VALUES (:id, :userId)
```

Three things about this are load-bearing, not stylistic:

- **Name only `id` and `user_id`.** `created_at` (exists today), `updated_at` and `version` (don't exist until V20) are all left to their column defaults. This is what makes the same statement valid against the current schema (`V16`) and the post-`V20` schema without an `if` anywhere — naming a column that doesn't exist yet would break pre-migration, and naming one that gets a default forfeits the "one instant, two equal timestamps" property design.md calls out: *"Revision 2 supplied `created_at` explicitly while letting `updated_at` default, which would have contradicted the equal-timestamps semantics it asserted."* Do not add `created_at` to the column list even though it already exists — the whole point is both timestamp columns come from the same database-evaluated `now()` in one statement, once `updated_at` exists to compare it against.
- **Bind `userId.toString()` explicitly.** The gateway generates a `UUID` (`SignupService.java:43`); `portfolios.user_id` is `VARCHAR(255) NOT NULL` (`portfolio-service/src/main/resources/db/migration/V1__Initial_Schema.sql:15`) — the same type mismatch `UserCredentialRepository.insertCredential` doesn't have to handle, because `user_credentials.user_id` and `users.id` are both `UUID`. Relying on an implicit driver or Postgres cast here is exactly what task 2.2's proof exists to catch if it's wrong — don't leave it implicit.
- **`version` defaults to `0`.** Requirement 5.16: a portfolio provisioned at signup (or by the Wave 3 backfill) must have `Portfolio_Version` `0`. `V20` will define `version BIGINT NOT NULL DEFAULT 0`, so simply not naming the column satisfies this once V20 exists — no application-side default to get wrong.

### 1.3 Where the insert method goes

Follow `UserCredentialRepository`'s existing shape (`api-gateway/src/main/java/com/wealth/gateway/auth/UserCredentialRepository.java:48-63`, `insertUser`/`insertCredential`) — same class, same `NamedParameterJdbcTemplate` field, same `MapSqlParameterSource` style. Add `insertPortfolio(UUID id, UUID userId)`, generating `id` the same way `provision()` already generates the user id (`UUID.randomUUID()`, `SignupService.java:43`) and binding it explicitly. Both tasks.md and design.md are prescriptive here — "`INSERT INTO portfolios (id, user_id)`" names both columns — not merely descriptive of one option among several; do not rely on `gen_random_uuid()`'s column default instead.

### 1.4 Prove it against real Postgres, not a mock

Two obligations here, proved by two different tests — neither substitutes for the other.

**Obligation A — call ordering, mandatory, on `SignupServiceTest`.** The real-Postgres before/after row count in Obligation B (below) proves *no portfolio persists* on a signing failure, but it cannot by itself distinguish "inserted, then the transaction rolled it back" from "never inserted because `insertPortfolio` isn't actually wired in before `signHs256`" — both look identical from outside the transaction. `SignupServiceTest`'s mocked `TransactionTemplate` (`stubTransactionToRunCallback()`, lines 39-45) invokes the callback directly, which makes it the right tool for asserting sequence, not persistence. Add a test using Mockito's `InOrder` (`org.mockito.InOrder`, `Mockito.inOrder(...)`) against the mocked `repository` and `jwtSigner` together, asserting the calls happen in exactly this order:

```
repository.insertUser(...) → repository.insertCredential(...) → repository.insertPortfolio(...) → jwtSigner.signHs256(...)
```

This is required, not optional — it is the only test that actually proves `insertPortfolio` sits where §1.1 says it must (after the email `try/catch`, before signing), independent of persistence.

**Obligation B — persistence and rollback against real Postgres, on `AuthIntegrationTest`.** `api-gateway/src/test/java/com/wealth/gateway/auth/AuthIntegrationTest.java` runs the real Flyway migrations against a Testcontainers Postgres and drives `/api/auth/signup` through the full `ApplicationContext` (`@SpringBootTest`, `WebEnvironment.RANDOM_PORT`) — it already migrates to whatever is on `main` (`V16` today), so it needs nothing from V19/V20 and can run right now. Extend its two existing signup tests rather than adding new ones:

- **`signupProvisionsUsersAndUserCredentialsRows`** (lines 119-137) — add an assertion that exactly one `portfolios` row exists with `user_id` equal to the returned `userId`, alongside the existing `users`/`user_credentials` counts.
- **`signupRollsBackBothRowsWhenTokenSigningFails`** (lines 141-172) — this test already forces a `JOSEException` out of `jwtSigner.signHs256(...)` (via `@MockitoSpyBean`) to exercise the genuine rollback path against a real transaction, and already asserts zero `users`/`user_credentials` rows persist. `portfolios` has no email column and the generated `userId` is never returned on a `503`, so there's no row to query by directly — capture `SELECT count(*) FROM portfolios` **before** the failing signup call, and assert the count is unchanged **after**. That proves the rollback discarded the portfolio insert along with the other two, the same way the existing assertions prove it for `users`/`user_credentials` by a different mechanism (querying by email, since those rows *do* have one).

This is task 2.1's integration evidence: real Postgres, real transaction, real rollback — not inferred from a mock, and not a substitute for Obligation A's ordering proof either.

## 2. Task 2.3 — `/api/assets/**` gateway route

_Requirements: 2.8, 9.3_

Requirement 9.3: *"`GET /api/assets` MAY deploy dark once the Catalog_Module is available in `portfolio-service`, being read-only and side-effect free."* The catalog module is already live in `portfolio-service` (Spec A shipped it). This task adds only the **routing config** — the controller endpoint itself is task 4.11, a later wave, and does not exist yet. Confirmed: no `AssetController` or `/api/assets` reference anywhere in `portfolio-service/src` today. Routing to it now is intentionally routing to a `404` until 4.11 ships — that's the point of shipping it "dark."

Two files, both already have the pattern to copy:

- `api-gateway/src/main/resources/application.yml` (lines 14–45) — add a route entry alongside `portfolio-service` (lines 15–18):
  ```yaml
  - id: asset-discovery
    uri: ${app.routes.portfolio-url}
    predicates:
      - Path=/api/assets/**
  ```
- `api-gateway/src/main/resources/application-prod.yml` (lines 68–79 show the `portfolio-service` entry's full shape) — same route, but **with** the rate-limit filter block every non-internal prod route carries (`RequestRateLimiter`, `userOrIpKeyResolver`, `standardRateLimiter`, `retry-after-seconds: 1`, matching `portfolio-service`'s own entry exactly).

No `SecurityConfig.java` change needed — `pathMatchers("/api/**").authenticated()` (`api-gateway/src/main/java/com/wealth/gateway/SecurityConfig.java:41`) already covers `/api/assets/**` by the existing catch-all, and Requirement 2.13 wants it authenticated like every other `/api` route anyway. `application-aws.yml`, `application-azure.yml`, and `application-local.yml` declare no routes of their own (checked) — only the two files above need editing.

## 3. What's blocked, and why you shouldn't try to unblock it yourself

### 3.1 — Task 2.2 needs a database at V19, which doesn't exist reachably

The proof (per tasks.md): "The insert runs against a database at V19 and one at V20, exercising the `toString()` binding. A run from today's V16 or an unspecified baseline does not satisfy this." The existing pattern to model it on is `api-gateway/src/test/java/com/wealth/gateway/auth/AuthIntegrationTest.java` (Testcontainers Postgres, `Flyway.configure().locations("filesystem:../portfolio-service/src/main/resources/db/migration")...migrate()`, at `AuthIntegrationTest.java:59-77`) — Flyway's `.target(MigrationVersion.fromVersion("19"))` is the mechanism to stop a run at V19 for the "before" half of the proof.

That pattern only compiles and runs once `V17__*.sql` through `V20__*.sql` actually exist under `portfolio-service/src/main/resources/db/migration/`. They don't, anywhere reachable from `main`:

- `V17`–`V19` are Spec A's, sitting only on `feat/supported-asset-postgres-repair` — unmerged, deliberately, per Spec A's own changelog. (`feat/supported-asset-mongo-repair` is a separate held branch, checkpoint 9.7, with no Flyway migrations of its own — see §0.)
- `V20` is this spec's (task 3.1, nominally "Wave 3") and doesn't exist as a file anywhere yet either.

Authoring `V20` itself, once V19 is available, is legitimate implementation work under Requirement 9.2 and not gated the way *applying* it to production is (that's task 3.5). But V19 has to exist first, and getting it here means merging `feat/supported-asset-postgres-repair` specifically — a decision this kickoff is not authorizing.

### 3.2 — Tasks 2.4/2.5/2.6 are downstream of 2.2

2.4 is a STOP/GO gated on 2.2 being green. 2.5 (G2 serving proof) is a *production* observation of a deployed R-A revision, and 2.6 is R-A's own STOP/GO. Design.md's framing — Spec A's production completion is the predecessor of *this whole release graph* — reads as covering R-A's actual deploy too, not only Wave 3's migration. Even setting that reading aside, none of 2.4–2.6 can produce real evidence while 2.2 hasn't run at all.

## 4. Verified anchors (checked against `main` at `09f6a1f`)

- `SignupService.java` — `provision()` lines 36-67, `insertUser`/`insertCredential` calls at lines 45-46, `DuplicateKeyException` catch + `setRollbackOnly()` at lines 47-49.
- `UserCredentialRepository.java` — `insertUser` lines 48-54, `insertCredential` lines 56-63, both plain `NamedParameterJdbcTemplate` + `MapSqlParameterSource`, no `@Repository` component scan (deliberate — see the class doc comment).
- `GatewayAuthDataConfig.java` — confirms `gatewayDataSource`/`namedParameterJdbcTemplate` connect to the **same** Postgres `portfolio-service` migrates, gated on `spring.datasource.url` being present (line 30). No new datasource wiring needed for 2.1.
- `portfolio-service/src/main/resources/db/migration/V1__Initial_Schema.sql:12-17` — `portfolios.id UUID PRIMARY KEY DEFAULT gen_random_uuid()`, `user_id VARCHAR(255) NOT NULL`, `created_at TIMESTAMP NOT NULL DEFAULT now()`. Highest migration on `main`: `V16__Drop_Better_Auth_Tables.sql` (verified via `git ls-tree`).
- `application.yml:14-45` — route table, `portfolio-service` entry at lines 15-18 is the pattern to copy.
- `application-prod.yml:68-101` — same table with rate-limit filters attached per route; `portfolio-service`'s entry (lines 69-79) is the pattern, including `retry-after-seconds: 1` and `standardRateLimiter`.
- `SecurityConfig.java:32-43` — `/api/**` → `.authenticated()` catch-all (line 41) already covers `/api/assets/**`; explicit exemptions exist only for `/actuator/**`, `/api/auth/**`, three `*/health` paths, and `/api/internal/**`.
- No `AssetController`, `AssetDiscovery*`, or `/api/assets` reference anywhere in `portfolio-service/src` (grepped) — task 4.11 genuinely hasn't shipped.
- `.kiro/specs/supported-asset-integrity/tasks.md:419-430` — tasks 9.6 and 9.7, both `CHECKPOINT — IRREVERSIBLE`, both `- [ ]` unchecked.
- `docs/changes/CHANGES_SUPPORTED_ASSET_INTEGRITY_2026-08-19.md:239` — *"No Flyway migration shipped. `V17`–`V19` exist only on the held repair branch; B1 owns `V20`."*
- Branch provenance, checked by diffing both held branches directly (`git ls-tree -r --name-only origin/<branch> -- portfolio-service/src/main/resources/db/migration`): `feat/supported-asset-postgres-repair` has `V17`-`V19`; `feat/supported-asset-mongo-repair` tops out at `V16`, same as `main` — it carries no new Flyway migrations.
- `AuthController.java:54-56` — `POST /api/auth/signup` is a real, mapped, public endpoint. `SecurityConfig.java:34` — `/api/auth/**` is `permitAll()`. `frontend/src/app/(auth)/signup/page.tsx` — the frontend links to it.
- `AuthIntegrationTest.java:119-172` — the two tests to extend per §1.4, `signupProvisionsUsersAndUserCredentialsRows` and `signupRollsBackBothRowsWhenTokenSigningFails`; both already run real Flyway migrations against Testcontainers Postgres with no V19/V20 dependency.

Re-verify before editing; line numbers shift.

## 5. Definition of done (for the actionable slice — 2.1 and 2.3)

- `SignupService.provision()` inserts a portfolio row in the same transaction as the user/credential inserts, placed **after** the email-specific `try/catch` (§1.1) so its failures reach `ProvisioningFailedException` → `503`, not `DuplicateEmailException` → `409`.
- The insert statement names `id` and `user_id` explicitly (both bound, per §1.2/§1.3 — `id` is not left to the column default); `created_at`, `updated_at`, `version` are never named.
- `userId.toString()` is bound explicitly, not left to an implicit cast.
- **Both** §1.4 tests exist: `SignupServiceTest` gains a mandatory `InOrder` assertion proving `insertUser → insertCredential → insertPortfolio → signHs256`, and `AuthIntegrationTest`'s two existing signup tests are extended for real-Postgres persistence and rollback. Neither substitutes for the other.
- `/api/assets/**` routes to `portfolio-service` in both `application.yml` and `application-prod.yml`, with the prod rate-limit filter matching `portfolio-service`'s own entry.
- Full `api-gateway` test suite green: `./gradlew :api-gateway:test --no-daemon` (add `--no-daemon` if the local Gradle daemon hangs on startup, per this repo's own notes).
- Spec checkbox **2.1** and **2.3** ticked in `main`'s copy of `tasks.md`. Leave 2.2/2.4/2.5/2.6 unticked.
- PR opened, **not merged**. PR description states plainly that 2.2 onward is blocked on Spec A's `V17`-`V19` (checkpoint 9.6, `feat/supported-asset-postgres-repair`), that this PR does not attempt to unblock it, and that the PR itself stays open — blocked, ideally marked as a GitHub draft — until 2.2 and 2.4 are green. See §6.

## 6. Merge effects — do not merge until 2.2 and 2.4 are green

`api-gateway/**` is in `deploy.yml`'s `push: paths:` filter — merging fires an automatic production deploy of `api-gateway`, the same trigger mechanism as Wave 1's `portfolio-service` merge. This is **not** a case like Wave 1's R-0, where the change was genuinely inert and the only question was release timing. Signup is live and public today (§0), and 2.1 changes its behavior the moment it's deployed: every successful signup gains a portfolio row and a new failure mode. Task 2.4 exists specifically to gate that deploy on 2.2 being green — *"STOP/GO — G1 before deploy. Go: 2.2 green."* Since 2.2 cannot run until Spec A's checkpoint 9.6 lands, **this PR must not merge until 2.2 and 2.4 both clear**, full stop — not "flag it to the owner and let them decide the timing," the way Wave 1's R-0 worked. Open the PR, mark it blocked (a GitHub draft PR is a reasonable way to make this literally unmergeable rather than just documented), and leave it there.

**When 2.2/2.4 do clear and this PR is ready to merge**, merge alone, matching every prior wave's guidance — `deploy.yml`/`deploy-azure.yml` have no `concurrency:` group (tracked in `docs/todos/TODOS_2026-04-07.md`).

## 7. Escalate rather than decide

- Whether/when to merge Spec A's `feat/supported-asset-postgres-repair` / `feat/supported-asset-mongo-repair` branches, or to execute checkpoints 9.6/9.7 — entirely the owner's call, on Spec A's own timeline, not this wave's.
- Whether to author `V20` (task 3.1) before V19 is available, e.g. as a standalone file with no integration test able to run yet — raise it as a question rather than deciding either way.
- Any caller of `UserCredentialRepository` or the new `insertPortfolio` method this kickoff's grep missed — re-verify before relying on this document's line numbers blindly.
- Any temptation to implement task 4.11 (`GET /api/assets` controller) "while you're in the area" — it's a later wave's task, cited here only to explain why 2.3's route legitimately 404s for now.
- Any temptation to merge this PR "since the code is done and tested" — implementation being finished is not the same as 2.2/2.4 being green. Raise it as a question if the wait feels wrong; don't merge past it.
