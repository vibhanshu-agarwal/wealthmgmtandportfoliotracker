# B2 Task 8.9 — Decision 2 owner-authorization chronology, 2026-09-15

> **AUTHORITY RECORD ONLY.** This record documents authority already granted in the owner's chat; it
> does not itself grant any operation. All separately authorized historical reads have been consumed.
> No further Production read or retry, login, mutation, deployment, rollback, feature exposure,
> merge, or other GitHub publication authority follows from this record.

## Scope initially authorized

The retained owner chat explicitly states: “Yes, it covers decision to production execute proof
evidence publication both.” That authorized the bounded Decision 2 execute proof and publication of
its evidence. It did not authorize merge or feature exposure.

## Re-arm chronology

The operating session then stopped locally three times before the final complete sequence. The
owner provided a fresh affirmative message before each subsequent start. Chat timestamps are not
available in the repository, so this artifact records the preserved message order and binds it to
the UTC start times in the operator transcripts; it does not claim a timestamped chat export.

| Sequence | Owner message retained in chat | Transcript-bound start and outcome |
|---|---|---|
| Initial Decision 2 start | `Approved` | `2026-09-14T23:59:29.3274390Z`; preflight passed, one setup-token login occurred, then a local claim guard stopped before the execute verifier |
| Re-arm 1 | `Approved` | `2026-09-15T00:16:06.8191684Z`; local Docker inventory handling stopped before the activation wrapper or any Production request |
| Re-arm 2 | `Approved` | `2026-09-15T00:21:22.5636489Z`; read-only pre-wake Azure checks began, then local PowerShell warning promotion stopped before the gateway wake |
| Re-arm 3 | `Authorized` | `2026-09-15T00:26:59.5961827Z`; final bounded sequence ran and the raw verifier ended `class_2b` / NON-GO |

The first start is preserved in `C:\t89\run-b-operator-20260914-235929.transcript.txt`; the three
re-arms are preserved in the correspondingly timestamped `C:\t89\run-b-retry-operator-*.transcript.txt`
files. The final transcript is published as
[`run-b-decision2-operator-transcript-20260915.txt`](run-b-decision2-operator-transcript-20260915.txt).

## Current authority boundary

The owner separately authorized one read-only Log Analytics replay after independent review rejected
the uncaptured manual recovery summary. That approval covered exactly two retained historical
queries with complete capture, and no login, mutation, wake, deployment, cleanup, or retry. Two
local launch attempts stopped before any Azure process or query: antivirus blocked the first script
before parsing, and a strict-mode property-selection error stopped the second before transcript
creation. Absence of every planned output file was verified after each stop. The launcher then
issued two queries once each and recorded `retry-count=0`. Its success query was byte-identical to
the retained `portfolio-service` query, but its skip query incorrectly replaced only the event name
and therefore retained `ContainerAppName_s == 'portfolio-service'` instead of the retained
`api-gateway` filter. Independent review rejected that zero-row result as non-probative.

## Gateway-skip correction authorization

After that rejection, the owner wrote the exact message **“[Vibhanshu] Authorized per comments.”**
The operator recorded receipt at `2026-09-15T03:54:50.0510297Z` and bound it to the review's stated
scope: exactly one byte-identical replay of the retained `api-gateway` skip query, using the same
workspace, trace, UTC window, direct Azure CLI Python transport, and raw capture method; no retry,
login, mutation, wake, deployment, cleanup, rollback, exposure, or merge.

The one authorized invocation started at `2026-09-15T03:54:50.0982079Z`, exited `0` at
`2026-09-15T03:54:57.4846284Z`, returned raw stdout `[]`, and produced empty stderr. The transcript
records `query-invocations-performed=1` and `retry-count=0`. This second read authority is consumed.
No further Production read or any other operational or publication authority follows from it.
