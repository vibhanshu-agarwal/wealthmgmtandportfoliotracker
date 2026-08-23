# Review request: checkpoint 9.8 (R4) — first enforcement-flag code change, needs independent review before merge

## Context (self-contained — no prior conversation assumed)

Repo: `wealthmgmtandportfoliotracker`. **This is a portfolio/demo project, not a production business**
— worth knowing up front, since it recalibrates how much operational weight a finding should carry:
real engineering rigor is the point (this spec's design is deliberately production-grade), but there is
no real money, real customers, or real financial-loss exposure behind the data involved.

Spec `supported-asset-integrity` ("Spec A") has an irreversible production cutover in progress
(`.kiro/specs/supported-asset-integrity/tasks.md`, Task 9). Checkpoints 9.1–9.7 are done and verified
against live state, most recently the MongoDB repair (9.7, `MM.NS` → `M&M.NS`), executed and
independently re-verified with zero incidents. Ingress is still closed (9.5), the market-data refresh
Job is still suspended (9.3), and both are staying that way through this checkpoint.

**9.8 is "R4 deployed, still overridden"** — tasks.md's go-condition: "artifact deployed with defaults
`true` but Terraform overrides still `false`; behaviour unchanged. Reversible." This is the first
application-code change in the Task 9 cutover sequence (9.1–9.7 were all Terraform/operational). Branch:
`codex/spec-a-checkpoint-9.8-r4`, opened as [PR #137](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/137)
(commits `c72112f` the change, `7339133` this brief) — held there for this review first, per explicit
instruction from the repo owner not to merge R4 code unilaterally.

## What R4 actually requires — and a gap found while scoping it

`app.catalog.reject-unsupported-events` and `app.catalog.enforce-holding-invariant` have defaulted to
`false` everywhere since they were introduced (portfolio-service, market-data-service, insight-service —
Java `@Value`/`Environment.getProperty` fallbacks and `application.yml`, no profile-specific overrides in
any `application-*.yml`, confirmed by grep across all three services). design.md's Gates table describes
the intended mechanism as: artifact default `false` through R1–R3, `true` from R4; a Terraform-level
override holds it explicit `false` through R3b regardless, removed only at cutover to make removal (not
assignment) the action that enables enforcement — "belt-and-braces... until R4."

**Grepping `infrastructure/terraform/azure/main.tf` for either flag name (env var or property form)
returned zero matches before this change.** No such Terraform override has ever existed — for any of the
three services. This means 9.8 is not just an artifact default flip; it also has to *originate* the
override mechanism design.md assumes was already there, in the same change that flips the default,
otherwise there is no "override" for 9.8's go-condition ("Terraform overrides still false") to refer to.

## What this change does

**Application code** (defaults `false` → `true`, all six call sites + both `application.yml` blocks):
- `portfolio-service`: `PortfolioCatalogConfiguration.java:50,52`, `SupportedAssetValidator.java:22`
  (also its javadoc), `MarketPriceProjectionService.java:61`, `application.yml:33-38`
- `market-data-service`: `MarketDataCatalogConfiguration.java:50,52`, `application.yml:21-26`
- `insight-service`: `InsightCatalogConfiguration.java:44,46`, `application.yml:100-105`

**Terraform** (`infrastructure/terraform/azure/main.tf`, one `env_vars` block per service — new,
previously absent): adds `APP_CATALOG_REJECT_UNSUPPORTED_EVENTS = "false"` and
`APP_CATALOG_ENFORCE_HOLDING_INVARIANT = "false"` to `module.portfolio_service`,
`module.market_data_service`, `module.insight_service`. This is the belt-and-braces override — an
explicit env var wins over the Spring `application.yml` default, so effective behaviour is unchanged by
this deploy. 9.9 is meant to **remove** these two lines (not flip their value) in the same apply that
raises `min_replicas` 0 → 1 for the verification window.

**Tests**: three pre-existing canary tests (`CatalogEnforcementDefaultsTest`, one per service) assert the
packaged `application.yml` default literally — `packagedYamlDefaultsBothEnforcementGatesToFalse()`.
Updated to assert `true` (renamed to `...ToTrue`), since flipping the default is the entire point of this
checkpoint and these tests exist specifically to catch an *unintentional* flip, not to block this one.

**A finding worth flagging explicitly, not hiding in the diff**: `reject-unsupported-events` and
`enforce-holding-invariant` are only load-bearing in `portfolio-service`
(`SupportedAssetValidator.requireActive`, `MarketPriceProjectionService.gatedReject` — both grepped and
read directly). In `market-data-service` and `insight-service` the same two properties are read *only*
inside `catalogLoadedLogger` for the `catalog_loaded` startup log line — no gating logic consumes them in
those two services today. So this checkpoint's real behavioural surface is portfolio-service; the other
two services' flag flip is presently cosmetic (log-line accuracy only). This matches 9.9's go-condition
text, which only asks for the *log line* to report the new values on all three services, not for
behavioural proof on all three — but it's worth Codex confirming this reading of 9.9 is correct before
9.9 is attempted, since if it isn't, market-data-service/insight-service would need real gating logic
added first and that's a materially bigger change than what's described here.

## Verification already done

- `terraform validate` — passes (ran `terraform init -backend=false` first).
- `terraform fmt -check -diff` — the only drift reported is a pre-existing, unrelated alignment issue on
  `main` at a different `env {}` block (`MARKET_DATA_JOB_RUNNER_ENABLED`, added at checkpoint 9.3),
  confirmed present on `main` before this branch by running `fmt -check` against `main`'s copy of the
  file in isolation. This change introduces no new formatting drift.
- `./gradlew :portfolio-service:test :market-data-service:test :insight-service:test` — full suites,
  green (portfolio-service 193/193 after the canary-test update; the other two also green). No other
  test depended on the old default.
- Diff scope reviewed end-to-end (`git diff --stat` against `main`): exactly the 12 files described above,
  71 insertions / 24 deletions, nothing unexpected.

## What NOT to re-litigate

- Whether 9.1–9.7 were done correctly — out of scope, already closed and independently verified.
- Whether Terraform *should* have had this override mechanism from the start (design.md says it should
  have — that's a spec-vs-implementation gap from earlier work, not something to relitigate now). The
  question here is only whether *this* change correctly originates it.
- Whether to actually deploy/apply this — that's 9.8's execution step, which comes after this review and
  requires its own separate go-ahead from the repo owner. This review is scoped to the code change only.

## What's being asked

1. Confirm the Terraform-override gap finding is real (main.tf had no such override before this commit)
   and that adding it as new `env_vars` entries — rather than, say, a dedicated Terraform variable/toggle
   — is the right level of mechanism for what 9.8/9.9 need, consistent with how checkpoint 9.3 handled
   `MARKET_DATA_JOB_RUNNER_ENABLED` (a plain hardcoded literal, edited directly per checkpoint, no
   toggle variable).
2. Confirm the "only portfolio-service is behaviourally gated" reading above, and whether that changes
   what 9.9's go-condition should actually require of market-data-service/insight-service.
3. Anything else that would block a clean, reversible 9.8 deploy — this checkpoint is explicitly marked
   Reversible, so the bar is "does this correctly leave production behaviour unchanged," not "is this
   fully hardened."

Branch `codex/spec-a-checkpoint-9.8-r4` is pushed and open as [PR #137](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/137),
with CI (including the required checks that gated 9.6/9.7) running against it.

---

## Review resolution (Codex, PR #137)

- **P1 (execution order)** — confirmed and load-bearing: Terraform must be applied before the R4 image
  deploys, never the reverse, because the container-app module's
  `lifecycle { ignore_changes = [template[0].container[0].image] }` means an apply only ever touches env
  vars/scaling, not the running image — so applying first is safe. Deploying the image first would run
  portfolio-service's `true` defaults unshadowed, activating real enforcement early and violating this
  checkpoint's "behaviour unchanged" contract. Independently verified
  (`infrastructure/terraform/azure/modules/container-app/main.tf:30-34`) and now recorded as explicit
  9.8.1/9.8.2 sub-steps in `tasks.md`.
- **P3 (stale brief text)** — fixed above; the branch/commit/PR references now match PR #137's actual
  state.
- **Terraform mechanism** — confirmed correct: hardcoded `env_vars` entries, no dedicated toggle
  variable, consistent with checkpoint 9.3's precedent.
- **Gating scope** — confirmed: only portfolio-service is behaviourally gated; the other two services'
  flags are startup-log-only. 9.9 does not need new gating logic in those two services. `tasks.md`'s 9.9
  entry now states this explicitly, including that actual-enforcement proof only needs to come from
  portfolio-service while the startup-log check still applies to all three.
