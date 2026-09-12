# B2 Task 8.9 — Run A attempt, 2026-09-12 (NON-GO)

Companion record for [`rehearsal-20260912.json`](rehearsal-20260912.json).

The verifier issues no HTTP to the gateway during preflight, so its evidence file **cannot** record
the wake that preceded it. Everything about the wake lives here. Without this file the JSON is
indistinguishable from the committed 2026-09-11 record except for a single configuration value.

**Task 8.9 remains OPEN.** Nothing here is progress toward a GO.

## Authorization

One bounded wake and one bounded preflight, owner-authorized 2026-09-12 ("one wake and one bounded
preflight; no execute or retries"), scoped to
[the readiness packet](../../superpowers/plans/2026-09-12-b2-task-8-9-live-proof-readiness-packet.md)
merged at `main@02b944d6` (PR #264). Performed by the designated operator on a Windows host;
baseline checkout `2fee0202`.

## What happened

Rows marked **†** are **operator-reported and appear in no evidence file**. They are
testimony, not capture, and nothing on disk corroborates them.

| | |
|---|---|
| Pre-wake state **†** | `api-gateway--0000081` @ `sha256:090ad3ba…`, from a hand-run live read against the 2026-09-11 provenance |
| Wake request **†** | `GET https://api.vibhanshu-ai-portfolio.dev/actuator/health`, one request, no client timeout |
| Wake response **†** | **HTTP 503 after 56.374 s**, from `curl -w` |
| Replica observed **†** | `api-gateway--0000081-6c469996fc-tgr88`, from a hand-run `az containerapp replica list` returning one name. This identifier appears nowhere else in the repository |
| Verifier outcome | stopped at `RBAC rehearsal could not resolve a gateway replica`; `class_2a`, `non_go`, exit 1, 6 operations |
| Post-run state **†** | `api-gateway--0000081` @ `sha256:090ad3ba…` unchanged; no `--0000082`, from a hand-run read after the verifier exited. The JSON's `serving` block is an *in-run* read and does not speak to post-run state |

A replica was observed after the wake, so the wake appears to have worked. When the verifier reached
its own `replica list` call the replica was no longer resolvable; the likeliest cause is that the
~300 s scale-to-zero cool-down had elapsed — **an inference from the failure mode, not something
this evidence demonstrates** (see below). **The wake is consumed.**

Exact wall-clock timestamps for the wake and the verifier start were not captured, and the file's
write time was not preserved (git does not record mtime; the commit time is the only recoverable
clock). The only timing figure is the 56.374 s wake latency from `curl -w`, itself
operator-reported. That gap is a finding in its own right: any future attempt should record the
wake and verifier-start times explicitly.

## Disclosure: the run deviated from the packet's own stop rule

The packet states: *"The wake request must be allowed to complete with a 200 before the verifier
starts… On any non-200, stop and report — do not re-issue the request."* The response was **503**,
and the verifier was run anyway.

That decision was **recommended by the assisting agent, not by the operator**, on the reasoning that
a named replica had been observed and therefore the wake had worked. That premise is itself
operator-reported and uncaptured (see **†** above).

Part of that reasoning was wrong and should not be relied on again. It cited
[`API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md`](../../runbooks/API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md)
item 5 — "timeout/`503`/`000` during warm-up alone is not a binding failure" — as licence to
proceed. "Binding" there refers to the custom-domain *binding*, not to whether the result binds the
operator; and item 6 immediately requires **three consecutive TLS-verified HTTP 200s**, with any
non-200 resetting the count. The runbook does not proceed on a 503 either. So the packet's rule was
**not** stricter than the repo's documented practice, and no document in this repository authorizes
continuing after a non-200 wake.

It is recorded as a deviation regardless. One consequence matters for reading the JSON: with a 503
rather than a 200 there is no clean "started within ~2 minutes of the 200" anchor, so
*"the cool-down elapsed"* is an **inference** from the failure mode, not something the evidence
demonstrates.

## No ACR cleanup is owed

Re-derivable from the recorded operation list, and independently re-derived during review. The run stopped at operation
6 (`replica list`), and `_preflight` reaches `az acr login` and the two `docker pull`s only *after*
that point. The `operations` array contains six entries, all `kind: azure_cli`; there is no
`local_cli` entry (how `docker` would be recorded), no `acr` argv, no `exec`, no `log-analytics
query`. `_record_command` appends an operation *before* invoking it, so any command that had been
started would appear even if it failed.

**No production-registry token was written to the operator's Docker credential store and no
production image was pulled.** No `docker logout` and no image removal is needed.

This covers the verifier only; it cannot speak to anything run by hand outside it. Starting Docker
Desktop, which was required as a precondition, performs no production-registry authentication.

Corollary: the operator's **AcrPull permission on `wealthprodacr` is still untested**. The packet's
step covering the first exercise of that permission was never reached. `preflight.rbacRehearsed: false` is accurate.

## Reading the JSON

- It is byte-identical to `rehearsal-20260911.json` **except** that one value, `timeoutSeconds`, differs in its six occurrences:
  `15.0` → `600.0`. That is the configured `--operation-timeout-seconds` **cap**, not an elapsed
  time, and it is the only in-file fingerprint distinguishing this run from the previous one.
- `replica list` exited 0 with decodable JSON — a permission failure would have been recorded as
  `gateway replica read permission failed: …` instead. The raise fires on an empty list, a non-list,
  or a first entry without a name, and the response body is not stored, so the file shows *that* no
  replica resolved, not *why*.
- Fields whose names overstate what they record: `decisions.wave10Eligible: true` means only "no
  idle-threshold override was used"; `thresholdRestore.verified: true` means "nothing to restore";
  `classificationDetail.resolved: true` means the classification is terminal, **not** that the
  blocker is resolved; `classificationDetail.action: retry_fresh_end_to_end` is the generic
  pre-login funnel and is **not** authorization to retry — a further wake is a fresh owner decision.
  `keyAlignment.*` are static initial values, not observations.
- `serving` matches the 2026-09-11 provenance for both services, so the attestation held through
  the run.

## Hand-run commands outside the verifier

The "no ACR cleanup" conclusion above covers the verifier process. The list below is
**operator-reported from session scrollback, not a captured shell transcript**; no artifact on disk
corroborates it. With that caveat, the commands run by hand during the window were: `az account show`, `az containerapp show` (twice, for the
serving revision and image), `az containerapp replica list` (three attempts, two of which failed on
argument mangling), `docker version`, `git fetch`/`git checkout`, and the single `curl` wake. All
are read-only or local. **No `az acr login`, `docker pull`, `docker login`, or any mutating Azure
command was run by hand.**

## Likely cause, and what changed because of it

The wake and the run were issued by hand, one paste at a time, and three of those pasted commands
were defective — all the same fault: parentheses and nested quotes not surviving PowerShell → the
`az` batch shim → cmd, so `--query "length(@)"` arrived as `length(@`. The minutes lost to those
failures, plus paste latency, are the likeliest explanation for the window being spent -- the same
inference noted above, not a demonstrated fact. The packet had specified the wake and the run as
*one operator sequence in one window*; the step-by-step handover defeated that.

A wrapper is **proposed** in PR #266 as a result — `scripts/run_task_8_9_preflight.ps1`, not part of
this PR and unmerged at the time of writing. Its intent is to hoist every precondition ahead of the
wake, issue exactly one request, wait for a **ready** replica, and start the verifier with no human
step in between. Read that PR for what it actually does; nothing here vouches for it.
