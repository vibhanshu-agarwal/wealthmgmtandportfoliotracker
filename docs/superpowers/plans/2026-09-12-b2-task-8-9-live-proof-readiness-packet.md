# B2 Task 8.9 — live-proof readiness packet

**Baseline:** `main@2fee0202` (PR #263 merge, 2026-09-11T19:51:30Z). Task 8.9 is **OPEN**.
Current attested gateway revision: `api-gateway--0000081` @ `sha256:090ad3ba…`, from Deploy run
`34588465283`. This packet requests decisions only. Nothing here has been executed: no Azure call,
no credential access, no verifier run, no wake, no push.

---

## 0. The one structural fact that shapes both decisions

`preflight` and `rehearsal` are **the same code path**. In `scripts/verify_demo_reset_azure.py`,
`run_proof` calls `_preflight(...)`, and then:

```python
if config.mode in {"preflight", "rehearsal"}:      # line 1727
    evidence["verdict"] = {"go": None, "status": config.mode + "_passed", "errors": []}
    return ...                                      # returns here — nothing further runs
if not config.access_token or not config.demo_password:
    raise ProofError("execute mode requires injected setup token and demo password")   # line 1735
```

Two consequences the two decisions hang on:

1. **The Task 8.9 application credentials are not needed to finish the preflight.** They are read
   only after the `preflight`/`rehearsal` early return. The blocked replica/exec step sits *inside*
   `_preflight`. (Azure/ACR authentication is a separate matter and *is* required — see §1.)
2. **Neither mode can ever return GO.** Both set `go: None`. Only `execute` produces a GO/NO-GO.

So the two owner gates are **sequential and independent**: Decision 1 alone completes the
preflight; Decision 2 is required only for a later `execute`. They should not be granted together
as a bundle, and Decision 1 does not imply Decision 2.

---

## 1. DECISION ONE — bounded wake of the scaled-to-zero gateway

### Where the run stops today

`_preflight` runs in fixed order: provenance → subscription → ingress → per-service image+revision
→ Log Analytics workspace → **replica list** → everything enumerated in the next section.
The 2026-09-11 record (`docs/evidence/b2-task-8-9/rehearsal-20260911.json`) stops at the replica
list, the sixth operation:

```
az containerapp replica list --name api-gateway --resource-group wealth-azure-prod-rg \
  --revision api-gateway--0000081 -o json          # returned no replica
→ ProofError("RBAC rehearsal could not resolve a gateway replica")   # lines 621-622
verdict: {go: false, status: non_go}   classification: class_2a   preflight.rbacRehearsed: false
```

Everything before it passed. Those fields are populated *only after* their equality checks pass, so
their presence in the record is the proof of match — subscription, ingress
`api.vibhanshu-ai-portfolio.dev`, both attested image/revision pairs, and the workspace.

### Exact target

| | |
|---|---|
| Resource | Container App `api-gateway`, resource group `wealth-azure-prod-rg` |
| Revision | `api-gateway--0000081` — **this exact revision, no other** |
| Purpose | Bring up ≥1 named replica so `replica list` resolves, permitting the rest of `_preflight` to run |

### What a successful post-wake preflight actually does

This is more than a couple of reads, and the authorization should be granted against the real list.
After replica resolution, `_preflight` runs, in order:

| # | Operation | Notes |
|---|---|---|
| 1 | `az containerapp exec … --command "java -jar /probe.jar"` | non-disclosing presence probe; fixed argv, no secret. Requires a replica that is not merely listed but ready |
| 2 | `az acr manifest show-metadata` × 2 | one per service, by immutable digest |
| 3 | **`az acr login --name wealthprodacr`** | **authenticates to the production registry; requires a running local Docker daemon** |
| 4 | **`docker pull <image@digest>` × 2** | **two real image pulls — api-gateway is 291,811,463 B; portfolio-service is the larger, at ~314.5 MB on every recorded build. Writes to the local Docker image store** |
| 5 | `az monitor log-analytics query … "print task8_9_rbac_probe=1"` | fixed, constant query |
| 6 | `az containerapp revision show` | Wave 8 decision / environment read-back |
| | `evidence["preflight"] = {"passed": True, "rbacRehearsed": True}` | the flag is set **here**, before step 7 |
| 7 | `python -B scripts/derive_demo_golden_state.py` | Task 4.4a golden oracle. **Local, no network** (no `urllib`/`requests`/`socket`/`subprocess` import); validates the demo identity. `_preflight` ends `return _load_oracle(...)` (line 706) |

**Count the operations correctly — the rows above are categories, not commands.** Rows 2 and 4
each issue two commands (one per service), so the nine post-wake commands are: exec, two
`manifest show-metadata`, `acr login`, two `docker pull`, the KQL query, `revision show`, and the
oracle. The run *before* the wake already recorded **6** operations up to and including
`replica list` (`az account show`, `containerapp show`, `revision list` ×2, `workspace show`,
`replica list` — exactly the six in `rehearsal-20260911.json`).

**So a fully passing run records 6 + 9 = 15 entries in `evidence["operations"]`, not seven.**
Every command reaches that array via `_record_command` (verifier lines 436-447). Any rule phrased
against "the seven" would fire on `az account show` and disown a correct run — read the abort rule
below as "any command not issued by `_preflight` (verifier lines 559-709)".

The `preflight.passed` flag is set *before* the final operation. If the oracle fails, the evidence
will show `preflight.passed: true` alongside `verdict.status: non_go` — that combination is not a
contradiction and must not be read as a partial success.

All fifteen are recorded `mutating: false` — that flag means "does not mutate the *application's*
state", and it is accurate: none writes to Azure or to demo data. It does not mean "does nothing".
Steps 3 and 4 authenticate to the production registry and pull **both** service images to local
disk. Authorize with that in view.

**This run exercises production-registry permissions the preflight has never reached.** Steps 3
and 4 perform an `az acr login` against `wealthprodacr` and pull both service images by digest, so
the operator's AcrPull permission on the production registry is tested here for the first time. The
verifier's own labels say as much — "ACR authentication rehearsal" (step 3) and
"immutable image pull rehearsal" (step 4), alongside "Log Analytics query RBAC rehearsal" at
step 5.

**Any post-wake failure consumes the wake.** A timeout or an RBAC denial at *any* of the nine
commands ends the run, and re-waking requires a new owner decision. Such a failure is a tooling or
permissions result — it is not evidence of a deployment defect, and must not be recorded as one.

### Two different credential classes — do not conflate them

| Class | Examples | Status in this decision |
|---|---|---|
| **Task 8.9 application credentials** | `TASK8_9_ACCESS_TOKEN`, `TASK8_9_DEMO_PASSWORD` | **Forbidden.** Read from the environment at parse time in every mode (1999-2000) but never *required or checked* before line 1735, which the early return at 1727 precedes. Gated behind Decision 2. |
| **Azure / ACR authentication** | the operator's existing `az` session; the ACR token minted by `az acr login` at step 3 | **Required.** The preflight cannot complete without it. Already in use by every prior read-only run. |

The earlier "no credential use" phrasing was wrong: it is the *application* credentials that stay
out of this window, not the Azure/ACR authentication the preflight legitimately performs.

### ⚠ The wake mechanism is the whole risk, and the obvious one is wrong

**Do NOT wake by raising `min-replicas`.** Azure Container Apps splits changes into two scopes
([Revisions](https://learn.microsoft.com/en-us/azure/container-apps/revisions), "Change types"):

- **`properties.template`** — containers, image, and **scale limits and rules** — is
  *revision-scope*: changing it **creates a new revision**.
- **`properties.configuration`** — ingress on/off, secrets, registries, Dapr — is
  *application-scope*: changing it does **not** create a revision.

`minReplicas` lives under `template.scale`, so a min-replicas wake is a template change and
**creates a new revision**. (The repo illustrates the other half of the split:
`.github/workflows/terraform-azure.yml:87-88` describes an api-gateway *ingress* reopen/rollback as
an "in-place update, image unchanged" — application-scope, no new revision.)

That a new revision then *purges* its predecessor is on the record for this exact container app:

```json
{ "revision": "api-gateway--0000080",
  "changeKind": "configuration only; image digest unchanged from 0079",
  "status": "purged",
  "onlySurvivingRecord": "…maxInactiveRevisions=0 purged the revision itself." }
```

(`docs/evidence/b2-task-8-9/deployment-completion-20260911.json`, `supersessionChain`)

(The `changeKind: "configuration only"` label in that record is the evidence file's own loose
wording. That 0080 was in Azure's terms a *template* change is **inferred** — it created a revision,
and only template changes do — not read off the deploy run. The conclusion stands; the label should
not be read as Azure terminology.)

A min-replicas wake would therefore create `api-gateway--0000082`, **purge `--0000081`, and destroy
the very attestation the whole task is bound to.** The next verifier run would fail its revision
equality check, and the provenance packet would need re-attestation from scratch — the same
recovery that consumed PR #262. `min_replicas` for this app is additionally Terraform-managed
(`infrastructure/terraform/azure/main.tf:234`, `variables.tf:93-97`), so a CLI change is also
out-of-band drift.

**The revision-preserving wake is inbound traffic, and it is supportable here.** The app is
ingress-enabled with `min_replicas = 0` and **no custom scale rule**, so Azure's default HTTP
scaling rule is what governs it — and that rule wakes an ingress-enabled app from zero. Grounding,
all in-repo:

- `infrastructure/terraform/azure/variables.tf:93-97` — `api_gateway_min_replicas`, `default = 0`.
- `infrastructure/terraform/azure/modules/container-app/main.tf:51-63` — `dynamic "ingress"` with
  `external_enabled` / `target_port`; `template` (lines 65-67) sets only `min_replicas` /
  `max_replicas`, with no `http_scale_rule` or `custom_scale_rule` block anywhere in the module.
- `infrastructure/terraform/azure/scripts/assert_api_gateway_timeout_rollout_plan.py:208` treats
  `min_replicas != 0` as a plan violation for `api-gateway`.

Azure references: [Scaling in Azure Container Apps](https://learn.microsoft.com/en-us/azure/container-apps/scale-app),
[Revisions](https://learn.microsoft.com/en-us/azure/container-apps/revisions).

Inbound traffic scales the *existing* revision from zero without creating a revision or touching
configuration: it changes no Terraform-managed value and leaves the supersession chain intact.

**The owner must name the exact route and method — "a request to the ingress host" is too broad.**
The authorization should record one specific, source-proven, non-mutating request. For reference,
the source supports this candidate:

> `GET https://api.vibhanshu-ai-portfolio.dev/actuator/health`

- `api-gateway/src/main/java/com/wealth/gateway/SecurityConfig.java:40-41` — `/actuator/health` and
  `/actuator/health/**` are `permitAll()`; **every other** `/actuator/**` path is `denyAll()`.
- `api-gateway/src/main/resources/application.yml:73-89` (`include: health` at line 89; the
  rationale comment at 77-88) — the health subtree is the only actuator
  surface exposed, deliberately, against wildcard exposure.
- `api-gateway/src/main/java/com/wealth/gateway/JwtAuthenticationFilter.java:45-49` — actuator
  traffic is served by the gateway itself; every proxied route is `/api/**`. So this request wakes
  **only `api-gateway`** and does not reach `portfolio-service` or touch demo data.

It is an unauthenticated read requiring neither class of credential. The owner should confirm this
route and method, or name a different one with equivalent source proof, as part of the decision.

Issue it as a single `curl`-style GET, **not from a browser**: a browser also requests
`/favicon.ico`, which falls through to `anyExchange().authenticated()` and logs a 401. Harmless in
itself, but it is noise in exactly the logs a later Run B correlates. A single request activates one replica from zero,
well inside the `max_replicas = 3` ceiling.

### ⏱ The wake has a ~5-minute useful life — this is the tightest constraint in the whole plan

Azure's documented scale-to-zero behaviour is a **300-second cool-down** after the last request
([Scaling](https://learn.microsoft.com/en-us/azure/container-apps/scale-app), "Scale behavior").
The verifier issues **no HTTP to the gateway during preflight** — `requestCounts` are all zero by
design — so nothing it does keeps the replica alive. The wake does not hold the door open.

Therefore:

1. The wake request must be allowed to **complete with a 200** before the verifier starts. A cold
   start can hold the request for tens of seconds while ACA activates the replica; a replica in
   a revision that is still `Activating` may list a replica whose container is not yet `Running`
   or ready, and `exec` has no readiness gate — the verifier accepts any listed replica with a
   name. **Set no client-side
   timeout** — note that `docs/runbooks/API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md:292` uses
   `curl --max-time 30`, which can expire during a cold start; do not copy it here. **On any
   non-200, stop and report — do not re-issue the request.**
2. **Start the verifier within ~2 minutes of that 200**, so that `replica list` — the *sixth*
   command, after five `az` calls that each take seconds — lands comfortably inside the 300s
   cool-down. The five-minute figure is the hard deadline for `replica list` — strictly, for the `exec` probe
   that immediately follows it and also needs the replica — not slack for starting: an operator who starts at 4:30 has followed the wrong reading and loses the wake. Past
   the cool-down the app is back at zero and the run fails at precisely the old stopping point,
   having consumed the authorization.
3. If `replica list` fails anyway, **do not re-wake** — that needs a fresh owner decision.

This is why the wake and the preflight must be one operator sequence in one window, and why a
generous outer window does not substitute for starting the run promptly.

One honest caveat to record with the decision: the 09-11 evidence's "zero HTTP requests" property
is a statement about **the verifier's own** requests (`requestCounts`, all zero). An owner-performed
wake request is outside that boundary and should be disclosed as such in the next evidence file,
not quietly absorbed.

### Bounded duration and stop conditions

| | |
|---|---|
| Window | **A 30-minute outer authorization window**, inside which the verifier must **start within ~2 minutes** of the wake's 200 so `replica list` lands inside the 300s cool-down (see the timing section above). One run, no retry inside the window without a fresh decision. |
| ⚠ Timeout | **`--operation-timeout-seconds` must be raised explicitly.** Its 15s default applies to *every* operation including both `docker pull`s (verifier lines 648-654), and the api-gateway image alone is 291,811,463 bytes — clearing that in 15s needs ~155 Mbit/s sustained. Left at the default, the run very likely fails at step 4 **after** the wake is spent. Note the flag raises the cap for all operations, not just the pulls. |
| Preconditions | A running local **Docker daemon in Linux-containers mode**, with `docker` on the verifier process's PATH (steps 3-4 need it); an authenticated `az` session for the production subscription; the provenance path passed explicitly. Confirm all three *before* waking — each failure otherwise surfaces only after the wake is consumed. |
| Scope | One wake, one preflight run. Not a standing change. |
| Abort immediately if | the serving revision is anything other than `api-gateway--0000081`; the serving digest is not `sha256:090ad3ba…`; `replica list` still resolves nothing after the wake; any operation reports `mutating: true`; or any command appears that `_preflight` (verifier lines 559-709) does not issue. Compare the digest against the full value in `deployment-provenance-20260911.json`, not the truncated form quoted here. |
| Never in this window | demo login, application write, KQL beyond the single fixed `print task8_9_rbac_probe=1` rehearsal, cleanup, rollback, deployment, flag exposure, and any use of the Task 8.9 application credentials. Azure/ACR authentication is expected and permitted — see the credential-class table above. |

### Mandatory restoration and read-back

The verifier's own `thresholdRestore` machinery is **not** what restores this, and must not be
enlisted: it exists for `--threshold-override`, which is a different mutation entirely (see §5).
Restoration here is that the app returns to scale-to-zero on its own idle cooldown once traffic
stops — no operator action re-scales it, because no configuration was changed.

What must be **verified and recorded**, not assumed:

1. **A live read of the currently serving revision and digest** — `az containerapp show` /
   `revision list` against the running app — confirming `api-gateway--0000081` @
   `sha256:090ad3ba…`, compared against `deployment-provenance-20260911.json`.
2. **A live revision-list read confirming no `api-gateway--0000082` was created.** This must be a
   live read, not an inspection of the tracked JSON: `supersessionChain` in
   `deployment-completion-20260911.json` is a static record written on 2026-09-11 and **cannot
   prove anything about a revision a future wake might create**. Only the live API can. (The
   verifier already performs equivalent reads at the head of `_preflight`, so a clean
   `preflight_passed` on the post-wake run is itself this evidence — but it must be read as such
   deliberately, not assumed.)
3. The replica count returns to zero after the **300-second** scale-to-zero cool-down, confirmed
   by one later live read.
4. The new evidence file records the wake explicitly: route and method, mechanism, who performed
   it, timestamp, and that it was owner-authorized.

If (1) or (2) fails, Task 8.9's provenance is invalidated and the task returns to re-attestation —
that is the failure mode this section exists to prevent.

---

## 2. DECISION TWO — credential delivery for a later `execute`

**Not required for Decision 1.** Requested separately so it is ready when, and only when, an
`execute` run is authorized.

### What the verifier needs, by name only

| Variable | Consumed as | Purpose |
|---|---|---|
| `TASK8_9_ACCESS_TOKEN` | process environment | setup/readback bearer for the portfolio API |
| `TASK8_9_DEMO_PASSWORD` | process environment | demo login leg |

Both names are the defaults of `--access-token-env` / `--demo-password-env`
(`verify_demo_reset_azure.py:1955-1956`). The verifier reads them from the environment by name
(`environ.get(args.access_token_env, "")`, lines 1999-2000). **Values never appear as command-line
arguments**, so they are absent from shell history, `ps` output, and CI command echo.

Both prior names are dead and must be reissued, not recovered.

### Required properties of the delivery method

1. **Environment-only.** Injected into the verifier process environment. Never a CLI argument,
   never a file committed or written into the worktree, never echoed.
2. **Ephemeral.** Scoped to the single authorized `execute` run; revoked immediately after, whatever
   the outcome.
3. **Least privilege.** The token needs only the portfolio read/write the proof exercises.
4. **Owner-injected.** Delivered by the owner into the run environment. A prior attempt failed on
   credential process-inheritance; whatever method is chosen must be confirmed to reach the child
   process before the run is authorized — a wrong injection surfaces as `execute mode requires
   injected setup token and demo password` (line 1735) only after the run has already started.
5. **Never surfaced to an agent.** No agent should be able to read the values, and no step in the
   procedure should print, log, or diff them.

### Disclosure safety already in the tool

`redact_evidence` walks the entire evidence document and replaces both values with `[REDACTED]`
before it is written, and it filters empty secrets first
(`[secret for secret in secrets_to_remove if secret]`), so blank values cannot cause spurious
redaction. This is a real safeguard, but it protects the *evidence file* — it does not protect a
shell transcript, so property 1 still stands.

---

## 3. Next-run sequence

### Run A — complete the preflight (needs Decision 1 only)

The wake and the run are **one operator sequence, performed by the authorized operator** — not
split between people and not performed by an agent.

**Before waking** (each of these otherwise fails only after the wake is spent):

1. Confirm the serving revision is `--0000081` @ `sha256:090ad3ba…`.
2. Confirm the local Docker daemon is running **in Linux-containers mode** (a Windows-containers
   daemon fails the `linux/amd64` pull, after the wake is spent), and that `docker` is on the PATH
   of the process that runs the verifier (`_resolve_command_executable`, verifier 199-207, resolves
   via `shutil.which` on Windows).
3. Confirm an authenticated `az` session for the production subscription.
4. Have the full verifier command staged and ready to paste, including an explicit
   `--operation-timeout-seconds`. The cap applies to **each command individually**, so size it for
   the larger of the two image pulls — which is portfolio-service, not api-gateway. api-gateway's
   attested image is 291,811,463 B (`deployment-completion-20260911.json`). The attested
   portfolio-service image's own size is not recorded, but three earlier builds of that service
   are: 314,499,296 B and 314,499,695 B (`docs/evidence/b1-task-6-5/`, ACR `imageSize`) and, for the
   immediate predecessor revision `--0000095`, 314,504,159 B pulled
   (`docs/evidence/b1-r-c/task-7-7-authorized-execution-20260908.json:96`). Every recorded build of
   this service exceeds api-gateway's by ~22 MB, so **size the cap for at least 315 MB**. Choose generously — a large cap costs nothing on a passing run, it only
   lengthens a hang.
5. **Run from a checkout at `2fee0202`.** Step 6 compares the live environment against the local
   `application.yml` (`_authoritative_yaml_defaults`, verifier 512-531) and step 7 runs the oracle
   over local `config/seed-tickers.json`. A checkout that differs in either file fails the run
   *after* the wake is spent.

**Then, in one window:**

6. Issue the named wake request; **wait for the 200**.
7. Start the run **within ~2 minutes**: `--mode preflight`, with `--deployment-provenance
   docs/evidence/b2-task-8-9/deployment-provenance-20260911.json` **passed explicitly** — the flag
   is `required=True` with no default (line 1953), and the path is not the 09-10 one — plus
   `--evidence-output` to a new path under `docs/evidence/b2-task-8-9/`. **No `--threshold-override`.**
   The evidence document is written unconditionally, pass or fail.
8. Post-run live read-back per §1.
9. **Post-run hygiene.** The `az acr login` operation leaves a production-registry token in the
   operator's Docker credential store, and the two `docker pull`s leave both production images in
   the local image store. Log out of `wealthprodacr.azurecr.io` and remove the pulled images once
   the run is recorded.
10. Commit the evidence in the same PR that cites it, and have it independently reviewed.

If the run fails at step 4 on a pull timeout, that is a tooling result, not a deployment defect —
and re-waking needs a fresh decision.

**Success looks like:** `verdict: {go: null, status: "preflight_passed", errors: []}`,
`preflight: {passed: true, rbacRehearsed: true}`, **15** operations all `mutating: false`,
`requestCounts` all zero, `cleanup.armed: false`, `login: {}`.

`go: null` is the **correct and expected** result. It is not a GO and must not be reported as one.

### Run B — `execute` (needs Decisions 1 and 2, plus a separate authorization)

Not requested here. It is the first run that logs in, writes, queries KQL, and arms cleanup and
rollback. It requires its own owner decision after Run A's evidence is reviewed.

### Evidence checklist for whichever run happens

- [ ] `--deployment-provenance` pointed at the **2026-09-11** record, explicitly
- [ ] `--threshold-override` **absent** (see §5)
- [ ] `--operation-timeout-seconds` raised explicitly for the image pulls
- [ ] Docker daemon confirmed running before the wake
- [ ] verifier started within ~2 minutes of the wake's 200; `replica list` inside 5
- [ ] evidence shows **15** operations, all `mutating: false`
- [ ] Docker logged out of the production registry and pulled images removed afterwards
- [ ] the new evidence file will, like `rehearsal-20260911.json`, carry `target.subscriptionId` and
      `workspaceCustomerId` GUIDs — existing precedent on `main`, but confirm that is still intended
- [ ] serving revision/digest re-read **live** after the run and matching this packet
- [ ] **live** revision-list read confirming no `--0000082` (not an inspection of the tracked JSON)
- [ ] wake disclosed in the evidence: route and method, actor, timestamp, authorization
- [ ] evidence file committed alongside the prose that cites it, links checked against *that* run
- [ ] Task 8.9 checkbox still `- [ ]`; Wave 8 🟡; Wave 10 still gated
- [ ] independent review before any further authorization is acted on

---

## 4. What remains blocked, by decision

**Without Decision 1 (wake):** the preflight cannot pass. `replica list` resolves nothing, so the
exec probe, both ACR manifest reads, `az acr login`, both image pulls, the KQL rehearsal, the
Wave 8 decision read-back, and the Task 4.4a oracle are all unreachable, `preflight.rbacRehearsed`
stays `false`, and every run terminates `class_2a` / `non_go`. Task 8.9's live serving proof cannot
begin. Wave 10.2 stays blocked, since it requires that proof.

**Without Decision 2 (credentials):** `execute` cannot run at all — it raises before its first
request. No login, write, log-correlation, or GO/NO-GO verdict is obtainable. A preflight can still
pass; a proof cannot.

**Without either:** Task 8.9 stays open exactly as it is now. Nothing degrades — the attested
provenance remains valid indefinitely so long as nothing creates a new revision of **either**
attested service — `_preflight` checks `portfolio-service` too (verifier 571-598), so a
portfolio-service deploy invalidates it just as a gateway one does.

**Blocked regardless of both:** Task 10.2, feature exposure, repository variables, deploy dispatch,
rollback, and AWS. None is touched by either decision.

---

## 5. Two hazards to state explicitly

**`--threshold-override` must stay absent.** It is not a wake mechanism and must not be repurposed
as one. It issues a mutating `containerapp update` (`mutating=True`, line 1608) — which by §1's
evidence creates a new revision; the code acknowledges exactly this with
`allow_revision_change=config.threshold_override is not None`. It also **permanently forecloses
GO**: `verdict.go` requires `config.threshold_override is None` (line 1930), and an otherwise-
passing override run is downgraded to `diagnostic_only` (line 1936). An override-backed run can
never satisfy Wave 10.2.

**`decisions.wave10Eligible` in the evidence does not mean what it says.** It is
`config.threshold_override is None` (line 352) — "no idle-threshold override was used", nothing
about any Wave 10 gate. The 09-11 record carries `wave10Eligible: true` while `verdict.go` is
`false` and Wave 10 is firmly closed. No document currently cites the field; none should start.
A rename in the verifier is worth a backlog entry.

---

## 6. Provenance of this packet

Read-only. Sources: `scripts/verify_demo_reset_azure.py`,
`docs/evidence/b2-task-8-9/deployment-provenance-20260911.json`, `rehearsal-20260911.json`,
`deployment-completion-20260911.json`, `.kiro/specs/asset-picker-composition/tasks.md`,
`docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md`, `.github/workflows/terraform-azure.yml`,
`infrastructure/terraform/azure/{variables.tf, modules/container-app/main.tf,
scripts/assert_api_gateway_timeout_rollout_plan.py}`,
`api-gateway/src/main/java/com/wealth/gateway/{SecurityConfig.java, JwtAuthenticationFilter.java}`,
`api-gateway/src/main/resources/application.yml`, all at `main@2fee0202`. Line numbers are from
that revision.

**Revisions 7-10 (2026-09-12) — narrowing, simplification, the timeout correction, and its
provenance fix.** (These four revisions share one block rather than each taking a heading; a
self-describing "current revision" line was removed after it proved false the moment each new
revision landed.) The simplification removed the defect class rather than patching it. Three consecutive review rounds found an error of the same shape in this
paragraph: a **universal negative about program history** ("never exercised anywhere", "no
`docker pull` anywhere", "no evidence file records"); earlier rounds' headline defects were of
other kinds. Each was expensive to substantiate, each turned out to have a counterexample somewhere in
the repo, and none of them changed what the operator does. On the owner's instruction that paragraph
now retains only the two decision-bearing facts: this run exercises production-registry AcrPull and
`az acr login` for the first time in the preflight, and any post-wake timeout or RBAC failure
consumes the wake and requires a new owner decision. The two operations-table rows that carried the
same class of claim were simplified with it. Nothing about the operator procedure, the timing rule,
the operation accounting, or the abort conditions changed.

The review of this very simplification then found one instance of the class still standing in the
timeout guidance — "portfolio-service's size is not recorded anywhere in-repo", which earlier
reviews had accepted and which is false: `docs/evidence/b1-task-6-5/` records two earlier builds of
that service at 314,499,296 B and 314,499,695 B, and `task-7-7-authorized-execution-20260908.json`
records the immediate predecessor revision at 314,504,159 B pulled. Every one is **larger** than
api-gateway, so the guidance is now a number (size for ≥315 MB) rather than an assumption, and the
operator is told which of the two images actually binds it.

A final delta review confirmed that ordering by two independent routes: the ACR `imageSize` figures,
and Azure's own `systemPulledBytes` counts from a single run (api-gateway 291,792,374 vs
portfolio-service 314,504,159), which agree with the ACR figures to within 0.007% and put the gap
between the services at ~22 MB — far beyond build-to-build drift.

**Revision 6 (2026-09-12), after a narrow confirming pass on the revision 4→5 delta.** That pass
found revision 5 had **broadened a narrow statement into a false one**: "unexercised as mechanisms
anywhere in the program" was wrong for the exec — `az containerapp exec` was run against an
api-gateway serving replica in the owner-authorized 2026-08-26 G2 signup probe
(`docs/runbooks/B1_R_A_G2_SERVING_PROOF.md:39-45`). A fifth pass then caught the mirror-image error in that same fix: "no `docker pull` recorded
anywhere in this program" was also false — B1 R-C pulled the public MCR base image locally
(`verify_b1_candidate_image.py:155`). What is actually new is a pull from `wealthprodacr` and an
`az acr login`, and the packet now says only that. The word "identity" was dropped from the exec
comparison: no source records who ran the 2026-08-26 probe, so only the revision and command
differences are asserted. (For the record, revision 4's own wording was not strictly true either —
`docs/evidence/b1-r-c/task-7-7-serving-gates-20260908.json` records a Docker pull of `postgres`.) Also corrected:
`include: health` is at `application.yml:89`, not 90 (so the range is 73-89 — an error introduced by
revision 5's own citation fix), `_resolve_command_executable` spans 199-207, and the two row-level
"never yet exercised" flags now say precisely what is and is not new.

**Revision 5 (2026-09-12), closing the Minor items from the third review.** The third fresh
independent Fable review returned **ACCEPT** — no Critical, no Important — against revision 4, and
confirmed the six claims the owner named: the 15-operation accounting (6 pre-wake + 9 post-wake,
counted from the `_record_command` call sites including both `for service in SERVICES` loops), the
2-minute start / 5-minute `replica list` timing against Microsoft's documented 300s cool-down, the
checkout pin, both-service attestation scope, per-command timeout sizing, and the Docker hygiene
remedy. This revision closes its seven Minor items: five line-number citations were drifted (oracle
return 703→706, `_preflight` span 559-706→559-709, pull loop 645-651→648-654,
`_authoritative_yaml_defaults` 511-533→512-531, `application.yml` comment 76-81→77-88; note revision 6 had to
correct this group again); a stranded sizing fragment sat in the checkout-pin step instead of the timeout
step and is moved with a concrete floor; the "first-time path" framing was narrower than the
evidence — *all nine* post-wake commands are unexercised by this verifier, so an RBAC failure at the
KQL query consumes the wake exactly as a pull timeout would; a non-200 wake response had no stated
rule; the Docker precondition now specifies Linux-containers mode and PATH resolution; and two
wording nits (the activation-vs-steady-state scaling formula, and that `exec` is strictly the last
replica-dependent command). **These fixes are themselves unreviewed.**

**Revision 4 (2026-09-12), after a second, fresh independent Fable review returned ACCEPT WITH
CHANGES.** The critical defect was mine and was introduced by revision 3: the post-wake table's
rows are *categories*, two of which issue two commands each, so "seven operations" was a category
count. A fully passing run records **15** entries in `evidence["operations"]` (6 before the wake,
9 after). The abort rule and both success criteria were phrased against "the seven" and would have
fired on `az account show` — telling an operator to disown a correct run, the very failure class
revision 3 was written to remove. Also fixed: the timing rule said two incompatible things (start
within 5 minutes vs `replica list` within 5 minutes — `replica list` is the sixth command, so
starting is now ~2 minutes with the 5-minute deadline on `replica list` itself); the timeout was
sized for one image when step 4 pulls two, so it must be sized for the larger (revision 9 later
replaced that round's "portfolio-service's size is recorded nowhere in-repo" with the actual
figures); the application credentials *are* read at parse time in every
mode, just never required or checked; a portfolio-service deploy also invalidates the attestation;
the 0080 template-change reading is an inference and now says so; `application.yml` is cited 73-90;
the single-replica claim rested on `max_replicas` rather than the scale-up step. Added: the run must
execute from a checkout at `2fee0202` (steps 6-7 read local `application.yml` and
`config/seed-tickers.json`), and post-run hygiene for the ACR token and pulled production images.

**Revision 3 (2026-09-12), after independent Fable review returned ACCEPT WITH CHANGES.** One
critical and five further defects, all verified against source before editing: the default 15s
`--operation-timeout-seconds` also caps both `docker pull`s against a ~292 MB image, so the run as
specified would very likely have failed *after* the wake was spent — Run A now requires an explicit
raised timeout; a running Docker daemon was an unstated precondition; the ~300s scale-to-zero
cool-down means the wake has roughly a five-minute useful life, which the 30-minute window
obscured; `_preflight` ends `return _load_oracle(...)`, so there is a **seventh** operation after
the `preflight.passed` flag is set, and the old abort rule ("beyond the six") would have told an
operator to disown a passing run; the revision-scope reasoning used Azure's vocabulary backwards
(`minReplicas` is `properties.template` → revision-scope, which is *why* it creates a revision —
the conclusion was right, the stated rule was not); and `terraform-azure.yml:86` is about
catalog-consumer scale, not api-gateway, so that citation is replaced. Also added: both the exec
probe and the image pulls are first-time paths never exercised in this program.

**Revision 2 (2026-09-12), after owner correction.** Four changes, all verified against source
before editing: the post-wake step list was understated as three read-only steps and now enumerates
all six, including `az acr login` and two image pulls; "no credential use" was wrong and is now
split into Task 8.9 application credentials (forbidden) versus Azure/ACR authentication (required);
the ingress wake is now grounded in the Terraform module's `min_replicas = 0` with no custom scale
rule, and requires the owner to name a source-proven non-mutating route and method rather than "a
request to the ingress host"; and the post-wake check on `supersessionChain` — a static record that
cannot speak to a revision created after it was written — is replaced by a live revision read-back.

No Azure operation, secret access, verifier run, wake, login, write, KQL query, cleanup, rollback,
deployment, source change, push, PR, merge, or feature exposure was performed. This file is
untracked and uncommitted; committing it is a separate decision.
