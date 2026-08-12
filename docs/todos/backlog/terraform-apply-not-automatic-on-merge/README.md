# Backlog: Merged Azure Terraform Changes Don't Go Live Until Someone Remembers to Apply

**Status:** Open — 2026-08-12
**Owner:** unassigned
**Tracked in:** [Changelog — Phase 4 incident](../../../changes/CHANGES_NEW_USER_SIGNUP_PROFILE_2026-08-12.md)

---

## Status & Decision

**Open, not yet fixed.** This is the process gap that turned a reviewed, merged Terraform
fix into an hour-plus production outage of the entire signup/login feature. The immediate
incident is resolved (the pending apply was run manually on 2026-08-12), but the gap that
allowed it to happen at all — a merged infra change can sit unapplied indefinitely with no
signal to anyone — is still there and can recur for any future Terraform PR.

---

## What Happened (evidence, not speculation)

PR #85's final review (2026-08-12, Critical finding **C1**) added
`SPRING_DATASOURCE_URL`/`USERNAME`/`PASSWORD` injection into api-gateway's Container App in
`infrastructure/terraform/azure/main.tf`, mirroring `portfolio-service`'s existing pattern.
The change was reviewed, merged into `main`, and its `Terraform Azure Infrastructure` CI
check passed — but that check only runs `terraform plan` (validation) on a PR. `apply` only
runs on a manual `workflow_dispatch` (see `.github/workflows/terraform-azure.yml`'s header
comment: *"Apply path: runs ONLY on manual dispatch with action=apply"*).

Nobody ran it. For roughly an hour spanning two full app deploys (10:50 and 11:52 UTC),
api-gateway ran without the datasource variables, causing three compounding symptoms
(root-caused with direct Azure CLI + Log Analytics evidence — full detail in the changelog):

1. A 5-7-attempt crash-loop on every boot (`GatewayAuthDataConfig`'s `@ConditionalOnProperty`
   threw on the missing placeholder instead of cleanly evaluating false).
2. `/api/auth/signup` silently vanished from the route table as a side effect of the
   crash-loop recovery path (proven via `/actuator/mappings` + a clean local reproduction).
3. Login fell back to `GatewayAuthFallbackAutoConfiguration`, so no credential — including
   correctly-seeded ones — could ever succeed.

The gap was only caught because a live-site verification was explicitly requested after
merge. Nothing in CI, monitoring, or the deploy workflow itself would have surfaced it
otherwise — `deploy-azure.yml`'s own `verify` job passed throughout, since it doesn't
exercise `/api/auth/**`.

---

## Why It's Designed This Way (and shouldn't just be flipped)

`terraform-azure.yml`'s plan-on-PR / apply-on-manual-dispatch split is intentional — it
keeps unreviewed or unintended infrastructure changes from applying automatically the
moment a PR merges, which matters more for a solo/small-team project than convenience does.
Auto-applying on every merge to `main` would remove that safety margin and isn't
necessarily the right fix.

---

## Options (undecided — needs a decision, not just a code change)

- [ ] **A. CI reminder/gate.** Add a check (scheduled job or a step on `deploy-azure.yml`)
  that runs `terraform plan` against the live state and fails/comments loudly if it detects
  pending changes — turning "silently unapplied" into "visibly unapplied."
- [ ] **B. Deployment checklist.** A short, explicit step in the PR/merge process: "does this
  PR touch `infrastructure/terraform/`? If yes, apply is not automatic — trigger it before
  considering this done." Cheapest option, weakest guarantee (relies on remembering).
- [ ] **C. Auto-apply for a narrow, low-risk subset.** E.g., auto-apply only
  env-var/secret-value changes to existing resources (never resource creation/deletion),
  gated on the existing P1/P5 plan assertions already in the workflow. More engineering,
  meaningfully closes the gap for the exact class of change that caused this incident.
- [ ] **D. Accept the risk, do nothing differently** — but at minimum, add this file so the
  next occurrence isn't a fresh investigation.

No option has been chosen yet. Whoever picks this up should decide based on how often
Terraform changes actually ship relative to how much the manual-apply step is genuinely
remembered in practice.

---

## Cross-References

- [Changelog — full incident writeup, Phase 4](../../../changes/CHANGES_NEW_USER_SIGNUP_PROFILE_2026-08-12.md)
- [`.github/workflows/terraform-azure.yml`](../../../../.github/workflows/terraform-azure.yml) — the plan/apply split itself
- [`infrastructure/terraform/azure/main.tf`](../../../../infrastructure/terraform/azure/main.tf) — the C1 fix that was merged but not applied
- Related, narrower follow-up: `application-prod.yml`'s missing `:` defaults on datasource
  properties (why the missing env var became a crash-loop instead of a clean fallback) —
  logged in `docs/todos/TODOS_2026-04-07.md`, not a full backlog item on its own.
