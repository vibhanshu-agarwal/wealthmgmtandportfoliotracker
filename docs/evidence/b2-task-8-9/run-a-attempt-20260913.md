# B2 Task 8.9 — Run A attempt 2, 2026-09-13 (NON-GO, stopped at the wake)

Companion record for
[`run-a2-operator-transcript-20260913.txt`](run-a2-operator-transcript-20260913.txt).

There is **no** `rehearsal-20260913-run-a2.json`. The wrapper stopped on a non-200 wake before the
replica wait, so the verifier never started and wrote nothing. The transcript is the only
run artifact.

**Task 8.9 remains OPEN.** Nothing here is progress toward a GO.

**†** marks a claim **no committed artifact can corroborate** — observed in the operating session
(the assisting agent's tool output or the operator's terminal as relayed into that session) but not
captured to a file in this repository. It is testimony, not capture.

## Authorization

One Production gateway wake through the merged wrapper, followed by its read-only preflight; no
second wake if that attempt stops or fails. The scope was set by a Codex-authored kickoff dated
2026-09-13 **†** (held outside this repository) and confirmed by the owner in the operating session
with "yes, run it" **†**, given after the assisting agent listed the operator-role change, the
background-execution plan, the cold-start `503` risk from attempt 1, and the Docker hygiene plan.

Source under test: `scripts/run_task_8_9_preflight.ps1` as merged by PR #266 at
`main@6b9c70f6e4476e612d27bd1df320cf68e24dc76b` (accepted head `b2354946`, identical tree
`f6762e36`). This record is committed directly on top of that merge commit.

## Who ran it, and how — three deviations from the kickoff

The kickoff designated **Claude** as operator. Claude's launch of the staged script was **refused by
the Claude Code auto-mode permission classifier before execution** **†**; no process started
(transcript and evidence paths were confirmed absent, worktree clean, immediately afterwards **†**).
Claude did not retry or re-spell the command.

The **owner** then ran the staged script in their own terminal **†**. This departs from the
kickoff's "do not delegate the wake between agents or shells": the wake was issued from the owner's
process, not Claude's.

The outer shell also differed in form. The kickoff specified one Git Bash session. The actual
chain was an interactive **PowerShell** terminal → `C:\Program Files\Git\bin\bash.exe` (Git Bash)
running the staged script → `powershell.exe -File` the wrapper **†**. The owner's first invocation
used bare `bash`, which in that PowerShell resolved to `C:\WINDOWS\system32\bash.exe` (WSL) and failed
with `No such file or directory` before any line of the script ran; no transcript was created and
the worktree stayed clean **†**. The second invocation named Git Bash explicitly and is the run
recorded here.

Third, the kickoff asks for the pre-run checks "in the same Git Bash session before invoking the
wrapper". Checks 2 (Azure subscription), 3 (Docker) and 5 (image inventory) were run from the
assisting agent's own shells around `06:22Z`, not from the owner's session. Nothing material rests on
this. The staged script's guards re-ran checks 1, 4 and 6 in the owner's session. The wrapper itself
re-verified the Azure session, the subscription match and Docker's Linux mode before the wake
(transcript lines 5–8 and 13). Check 5 only matters for image cleanup, and this run stopped before
any pull.

### The staged script

`run-a2.sh`, sha256 `51e18afd5076ad0e67fac2b1e67e6a737b22298a32d2a5657e15936d7f2efa90` **†** (a
session scratch file, not committed). Everything from `set -o pipefail` onward was diffed as
identical to the kickoff's 18-line command block **†**. Before that block it ran these guards in the
same shell; any failure would have exited 90–95 before the wrapper started:

```bash
cd '/c/worktrees/wealthmgmtandportfoliotracker-worktrees/wealthmgmtandportfoliotracker-claude-task-8-9-run-a2' || exit 90
[ "$(git rev-parse --show-toplevel)" = 'C:/worktrees/wealthmgmtandportfoliotracker-worktrees/wealthmgmtandportfoliotracker-claude-task-8-9-run-a2' ] || { echo 'GUARD: toplevel'; exit 91; }
[ "$(git rev-parse HEAD)" = '6b9c70f6e4476e612d27bd1df320cf68e24dc76b' ] || { echo 'GUARD: HEAD'; exit 92; }
[ -z "$(git status --porcelain)" ] || { echo 'GUARD: worktree not clean'; exit 93; }
[ -z "${TASK8_9_ACCESS_TOKEN+x}" ] && [ -z "${TASK8_9_DEMO_PASSWORD+x}" ] || { echo 'GUARD: a TASK8_9 credential variable is set'; exit 94; }
for f in docs/evidence/b2-task-8-9/rehearsal-20260913-run-a2.json docs/evidence/b2-task-8-9/run-a2-operator-transcript-20260913.txt docs/evidence/b2-task-8-9/run-a-attempt-20260913.md; do
  [ ! -e "$f" ] || { echo "GUARD: exists $f"; exit 95; }
done
echo 'guards: ok'
```

The terminal printed `guards: ok` **†**. That line precedes the `tee` and is therefore not in the
transcript.

## Pre-run state (assisting agent, before the owner's run)

**Every bullet in this section is †**: assisting-agent tool output, not captured to any file. All of
it was read-only or local, and none of it issued ingress traffic.

- Sibling worktree created detached at `6b9c70f6`; toplevel and HEAD exact; `git status --porcelain`
  empty; `-q` is curl's first argument (wrapper line 154); the three evidence paths absent.
- `origin/main` was exactly `6b9c70f6`; `application.yml` and `config/seed-tickers.json` unchanged
  since `2fee0202`.
- Active `az` subscription equals `target.subscriptionId` in `rehearsal-20260911.json` (compared,
  never printed). Both `TASK8_9_*` variables absent in the parent shell and a PowerShell child.
- Docker Desktop was **not running**; the assisting agent started it. Linux engine `29.7.2`
  confirmed from a PowerShell child. Starting Docker performs no registry authentication.
- `curl.exe` in a PowerShell child launched from Git Bash resolved to Git's
  `mingw64\bin\curl.exe` (8.21.0, Schannel) rather than `System32\curl.exe` (8.21.0, Schannel). Both
  use the Windows certificate store. That it resolved the same way inside the owner's run is an
  inference from the same launch chain, not an observation.
- Image inventory (daemon reachable, no pull):
  `wealthprodacr.azurecr.io/api-gateway@sha256:090ad3ba…` **absent**;
  `wealthprodacr.azurecr.io/portfolio-service@sha256:1cf372a3…` **already present**. The Docker
  config already held a `wealthprodacr.azurecr.io` entry under `auths` (`credsStore: desktop`).

## What happened (from the transcript)

The transcript is committed byte-for-byte as written: 28 lines, 1,755 bytes, sha256
`e81362f5cd08d166aa8d33d532493e261168563b71e6b4cdb30e2e06fb4e663d`. Its four `WARNING` lines end in
CRLF because `az` wrote them that way; line-ending conversion was disabled when staging it so they
survive.

| | |
|---|---|
| Operator start | `2026-09-13T06:39:51Z` |
| Pre-wake checks | all passed: attestation `api-gateway--0000081` @ `sha256:090ad3ba…`; subscription resolved (length 36); wake request is the authorized one; `az` session; Docker `linux`; gateway serving `--0000081` at the attested digest; active subscription matches; `portfolio-service--0000096` at its attested digest; workspace readable; verifier starts; 1 gateway revision, none newer |
| Wake request | `GET https://api.vibhanshu-ai-portfolio.dev/actuator/health`, one request, `-q`, no retry, no redirect, no client timeout |
| Wake response | **HTTP `503`, curl exit `0`** |
| Wrapper decision | `FAIL: the wake returned HTTP 503, not 200 … The wake is consumed` |
| Replica wait | not reached |
| Verifier | not started; no JSON written |
| Operator end | `2026-09-13T06:41:13Z` |
| Wrapper exit | **`3`** |

**Not captured:** the wake's own timestamp and its latency. The transcript has no per-line clock;
the wrapper's `-w '%{http_code}'` records status only. The wake happened after the pre-wake checks
and before `06:41:13Z`, inside an 82 s run. Attempt 1's record,
[`run-a-attempt-20260912.md`](run-a-attempt-20260912.md), asked that future attempts record wake and
verifier-start times explicitly; the merged wrapper does not. That record was merged to `main` via
PR #265 at `e73ab8e9` (2026-09-13 `06:38:31Z`), after this record's parent `6b9c70f6`, so it is not
in this commit's tree.

**Also not captured:** the `503` response body and headers (`-o NUL`). This evidence cannot say
whether the `503` came from the Container Apps ingress (no ready upstream during activation) or
from Spring Boot's actuator reporting the application `DOWN`.

## Post-run read-backs (assisting agent)

**Every row in this table is †**: assisting-agent tool output, not captured to any file. None of
these reads issued ingress traffic or re-ran the wrapper.

| Read (UTC) | Command (abridged) | Result |
|---|---|---|
| `06:48:15Z` | `az containerapp show -n api-gateway` — `latestReadyRevisionName`, `latestRevisionName`, `template.containers[0].image` | `api-gateway--0000081`, `api-gateway--0000081`, `…/api-gateway@sha256:090ad3ba4b7ada20bca71781d4a1ce3ac81029b8a23a8a1d180dce2cff540d09` |
| `06:48:15Z` | `az containerapp show -n portfolio-service` — `latestReadyRevisionName`, `template.containers[0].image` | `portfolio-service--0000096`, `…/portfolio-service@sha256:1cf372a39d17709f74aba427259548a531e3e1ba04dc64ec7750369bb8e82126` |
| `06:48:15Z` | `az containerapp revision list -n api-gateway --query [].name` | exit 0, count 1: `api-gateway--0000081` — no `--0000082` |
| `06:48:54Z` | `az containerapp replica list -n api-gateway --revision api-gateway--0000081 -o json` | exit 0, `[]` — **zero replicas** |

The replica read was 461 s after operator end, so at least 300 s after the wake. `[]` shows the
gateway at zero replicas at that moment. It does not show whether a replica started after the `503`.

## Hygiene

- **ACR login: not performed by this run.** The verifier never started, and the wrapper itself
  issues no `az acr login`. The kickoff conditions `docker logout` on the evidence recording an ACR
  login, so **no logout was run**. A `wealthprodacr.azurecr.io` `auths` entry was present before the
  run and still present after it **†**. Only its presence was checked, not its content. It predates
  this run.
- **Images: nothing pulled, nothing removed.** Post-run inventory is identical to pre-run: the
  gateway image `@sha256:090ad3ba…` is still absent; the four `wealthprodacr` images present
  before (`api-gateway@aee44edc…`, `portfolio-service@1cf372a3…`, `@551fa974…`, `@fa060bf0…`) are
  unchanged **†**.
- Consequently the operator's AcrPull on `wealthprodacr`, and `preflight.rbacRehearsed`, remain
  **untested**.

## Why it stopped, and what that implies

The wrapper did exactly what it was written to do: one request, non-200, stop, exit 3. There was no
deviation from the stop rule this time.

The stop was **predictable**. This is the second attempt in two to receive a `503` on the cold wake
(attempt 1: `503` after 56.374 s, operator-reported in `run-a-attempt-20260912.md`). The
repository has recorded this gateway starting cold before.
[`API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md`](../../runbooks/API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md)
lines 47–50 describe a first contact on the custom hostname that timed out after 25 s, then three
consecutive `503`s, then a `200` after 17 s. The runbook calls that "consistent with a
scale-from-zero cold start". That runbook's post-bind read-back rule, items 5–6, says a
warm-up timeout/`503`/`000` "is not a binding failure". It then requires **three consecutive** `200`s,
each bounded by `--max-time 30`, logging `http_status`, `curl_exit` and `duration_s`. Those items
govern the custom-domain restore, not every use of the endpoint. Still, a design of one request that
must return `200` does not fit this gateway's recorded cold-start behaviour. That is an inference from
two observations and the runbook, not a demonstrated cause.

Until the wake design changes, another attempt of this design is likely to stop the same way. Any
further wake is a fresh owner decision.
