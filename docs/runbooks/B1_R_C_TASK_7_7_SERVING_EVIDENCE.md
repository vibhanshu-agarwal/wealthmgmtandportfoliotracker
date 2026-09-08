# B1 R-C Task 7.7 — pre-deploy serving evidence

> ## OWNER APPROVAL REQUIRED — live collection remains blocked
>
> **Blocked action:** read current Azure Container App serving metadata, query production logs and
> run the required read-only Neon snapshots. The task host rejected that live-access request and
> requires fresh explicit owner approval in this task. If approved, collection can resume within the
> frozen read-only contract below. If declined or deferred, Task 7.7 remains open and Task 7.8 must
> not be presented.
>
> A separate approval would be required before any signup, seed/reset, legacy-route POST probe or
> other production write. Task 7.7 cannot be called green if its accepted oracle requires one of
> those operations and it remains unauthorized.

**Status:** BLOCKED BEFORE LIVE COLLECTION. No Task 7.7 gate is accepted by this record.

**Recorded:** 2026-09-08. **Baseline:** fetched `main` merge
`7e752b4183aa25f75adad6e7b363501cd5f23aa5` (PR #238), tree-identical to this task's detached
checkout. **Machine-readable record:**
[`task-7-7-serving-gates-20260908.json`](../evidence/b1-r-c/task-7-7-serving-gates-20260908.json).

## 1. Scope and stop boundary

The owner assigned Task 7.7 only and excluded Task 7.8, deployment, workflow dispatch, production
writes, traffic/configuration changes, rollback, public exposure and Writer_Convergence. The frozen
collection contract therefore permits only control-plane GETs, read-only log queries, read-only
container GET probes and `REPEATABLE READ READ ONLY` SQL. It permits zero signups, zero seed/reset
calls and zero legacy-route POST probes.

The attempted Azure account/Container App metadata query did not execute: the task host approval
reviewer denied live cloud access and required a fresh owner approval in this task. No secret was
read, no database connection was opened and no public endpoint was called. That denial was not
bypassed.

## 2. Verified local and tracked bindings

The requested baseline fetch completed in Codex's assigned persistent checkout. `FETCH_HEAD` and
`origin/main` both resolved to `7e752b4183aa25f75adad6e7b363501cd5f23aa5`, merge subject
`Merge pull request #238 from vibhanshu-agarwal/codex/b1-rc-predeploy-evidence`. The managed task
checkout has the same tree.

The merged checkpoint independently verifies the asserted R-C candidate cut
`8f1e8a36f8baa594efa8079190f87b42139fcf10` and deployable `linux/amd64` manifest
`wealthprodacr.azurecr.io/portfolio-service@sha256:1cf372a39d17709f74aba427259548a531e3e1ba04dc64ec7750369bb8e82126`.
That evidence ends at Task 7.6 and is not serving proof.

The latest tracked serving record is not the older cu4 runtime summarized in the master plan.
R-B3r deployed portfolio revision `portfolio-service--0000095` at digest
`sha256:fa060bf054b9c108b8b59d9e9b27845d6b707f40040a7dcba16411db7f0e8552` on 2026-09-07,
superseding `0000094` / `2be727ea…`. The accepted R3 remediation record is
[`v21-r3-operational-result.json`](../evidence/b1-r3-decision1/v21-r3-operational-result.json).

## 3. Evidence oracle and current result

| Gate | Required Task 7.7 evidence | Reusable evidence | Result |
|---|---|---|---|
| G2 | Complete current gateway serving set, immutable digest/traffic binding and provisioning proof | R-A method and historical probe only | **UNVERIFIED** — current serving metadata and controlled signup were not collected; historical `0000076` cannot be silently applied to later `0000077` |
| latest-valid G3 | Relational `users LEFT JOIN portfolios ... HAVING COUNT(p.id) <> 1` snapshot after latest valid G2 | Query shape only | **UNVERIFIED** — no current read-only SQL snapshot |
| G4 | Current Spec A artifact/catalog identity, effective enforcement on all three consumers, portfolio behavioral enforcement, V17–V21/repair state, refresh steady state and bounded error/consumer observations | Spec A runbooks as provenance | **UNVERIFIED** — no current live observation |
| G0a | Every current serving portfolio revision/digest lacks both retired writers | Historical probe shape and R-B3r source/proof context | **UNVERIFIED** — R-B3r superseded the older binding and no safe current complete-set proof was collected |
| G2a | Numeric Portfolio_Version from every current serving portfolio digest | R-B2 method; R-B3r ordinary read as supporting history | **UNVERIFIED** — no current complete serving-set read-back/probe |
| G2b | Every serving portfolio digest requires version and delegates; controlled proof preserves identity and the complete Spec A price/schema state | Accepted cu4 protocol and R-B3r SAME_STATE seed as history | **UNVERIFIED** — R-B3r superseded cu4; its remediation summary lacks the complete Task 7.7 before/after price/schema oracle, and no new write is authorized |
| G6 | Fresh serving G0a + G2a + G2b, joined to exhaustive writer coverage for the serving and candidate cuts | Task 7.6 candidate inventory | **UNVERIFIED** — the serving gates are incomplete and the inventory still needs an explicit serving-cut mapping |

G3 must be collected after the latest valid G2 evidence and after any other authorized production
writes. One successful load-balanced request cannot prove a universal serving gate: collection must
enumerate every active revision, nonzero traffic destination, revision-label route and otherwise
addressable serving revision, with pre/post drift checks.

## 4. Historical evidence that may be reused

- Candidate Task 7.3–7.6 identifiers, hashes, exact-digest smoke and exhaustive candidate writer
  inventory from the September 8 checkpoint.
- The accepted R-B3r V21 record for its exact source, digest, revision, one successful migration,
  absence of the four transient repair routines, ordinary version-bearing read, one SAME_STATE seed
  outcome and retired public composition-route result.
- Earlier G2/G3/G0a/G2a/G2b runbooks as protocol and historical observations only.

Reuse requires exact source/artifact/configuration bindings and current invalidator checks. Historical
checkboxes, mutable tags, `SERVICE_VERSION`, old G3 counts and old approvals are not current proof.

## 5. Resume plan after authorization

1. Enumerate and bind complete gateway, portfolio, market-data and insight serving sets, replicas,
   traffic, ingress, immutable digests and relevant effective configuration; stop on mixed or
   unexpected serving state.
2. Establish the latest valid G2 evidence within the separately approved probe/write count.
3. Revalidate G4 without replaying repair/refresh or changing flags, scale, ingress or schedules.
4. Revalidate G0a, G2a and G2b across the entire current portfolio serving set. Any permitted G2b
   attempt must follow the accepted one-attempt protocol with separate complete BEFORE and AFTER
   read-only transactions, no retry and no inherited rollback authority.
5. Recollect final G3 in a read-only transaction after the latest valid G2 evidence and after every
   other authorized write-bearing probe.
6. Map the Task 7.6 inventory exhaustively to the R-B3r serving cut, derive G6, sanitize and hash
   private captures, and obtain an independent whole-packet review.

## 6. Decision boundary

Task 7.7 remains unchecked. Task 7.8 cannot be presented to the owner from this packet. The R-C
candidate remains undeployed. The latest tracked runtime is R-B3r; current serving state was not
reverified. No Writer_Convergence or public-exposure claim is made.

## 7. Independent future development work — unstarted

The following source/process tasks are independent of Task 7.7 but require separate owner approval
before assignment. None was started here:

- **Terra:** Spec A Task 8.8, replace remaining hard-coded catalog-size assertions with the
  Active_Asset cardinality while retaining explicit catalog-version checks.
- **Luna:** B2 Task 2.7, reconstruct and document the historical backend-before-adapter frontend
  artifact, routing/cache and rollback-containment window; no fresh cloud access is included.
- **Terra:** B2 Task 10.1 source-only CI/CD flag wiring with both repository variables remaining
  unset. Creating variables, enabling either flag, deploying or exposing production remains outside
  that development assignment.
