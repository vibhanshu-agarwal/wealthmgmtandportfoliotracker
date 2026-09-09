# B1 R-C Task 7.9 — exact-digest serving proof, 2026-09-09

## Owner approval boundary

The owner explicitly approved the bounded Task 7.9 digest deployment, necessary cloud and secret
reads, one authenticated no-op composition `PUT`, a contingent exact R-B3r rollback, one
`GET /api/insights/health` wake, and the GitHub production Environment gate approval. Publication,
push, PR/merge, Task 7.10, Writer_Convergence, and any broader action remain closed.

The health wake had its own direct owner approval in the active Codex session: Sol disclosed that
one production `GET /api/insights/health` would wake scale-to-zero insight-service and commit
offsets, then asked for approval of that one production GET. The owner replied, “Approved. Please
go ahead.” No external artifact or session ID is asserted.

**Task 7.9 is complete. Task 7.10 is not started or decided by this record.** R-C is
portfolio-only but not dark: `Path=/api/portfolio/**` means the composition controller is publicly
reachable when the new portfolio revision takes traffic.

## Exact artifact and rollback floor

| Role | Binding |
|---|---|
| Dispatch main / candidate source | `main@5fd1dac6a37513916fd2b80ca3929c4e20ad0de7` / `8f1e8a36f8baa594efa8079190f87b42139fcf10` |
| Candidate JAR | `441d252939d7333dca134b9cc1a5f6a0632cc274a53f410671212c543a85cda4` |
| Deployed linux/amd64 image | `wealthprodacr.azurecr.io/portfolio-service@sha256:1cf372a39d17709f74aba427259548a531e3e1ba04dc64ec7750369bb8e82126` |
| Safe floor, not used | R-B3r `portfolio-service--0000095` / `sha256:fa060bf054b9c108b8b59d9e9b27845d6b707f40040a7dcba16411db7f0e8552` |

No rollback occurred. The floor is retained for the separate Task 7.10 decision only.

## Pre-dispatch state

- Required main CI [34324649773](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/34324649773) completed successfully.
- The 2026-09-09 refresh `market-data-refresh-job-29815680` succeeded from 08:00:00Z to
  08:01:12Z and advanced `market-prices` to offset 26760.
- The read-only database preflight passed: 12 users/12 portfolios, `violating_users=0`, V17–V21
  successful with V21 once/checksum `385711525`, zero repair functions, validated constraints,
  null quantity default, zero bad/null portfolio state, 159 active holding tickers, and no
  BTC/MM.NS legacy holdings.
- Through fixed end `2026-09-09T08:04:52.6970664Z`, every observed pull replica of portfolio
  `0000095`, market `0000080`, and insight `0000080` emitted one startup tuple
  `a00b32ac0267e1a9|160|159|true|true`; bounded errors were empty.
- The existing authenticated E2E identity read its one portfolio
  `d61870f5-d420-4947-987c-401e36d2069f` at version 0 with 159 holdings. The sanitized
  pre-deploy response hash prefix was `6e9fa170`.

After the refresh, portfolio Kafka was `26760/26760` lag 0. Insight was scale-to-zero at
`26602/26760` lag 158, so the separately approved one health GET started existing revision
`0000080` and committed offsets. Both groups then reached `26760/26760` lag 0 and DLT stayed at
80. No signup, seed, reset, or cleanup occurred.

## Approved workflow deployment

The GitHub production Environment required owner reviewer id `105557418`; main branch policy and
the current user's approval eligibility were verified. The owner-authorized approval was applied to
Environment `20423330960`.

Workflow [34328692256](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/34328692256)
succeeded on `main@5fd1dac6` with `deployment_mode=digest`, `services=portfolio-service`, the
matching expected-main SHA, and the exact immutable reference above. Validation, authorization,
route, preflight, portfolio deploy, and non-interference succeeded. AWS, ACR login, build, digest
publish, refresh-job update, aggregate, frontend, seed, and verify were skipped. The workflow's
unselected-app/job byte-identical assertion succeeded.

`portfolio-service--0000096` was created at 08:25:59Z and is the sole latest/ready active revision
in `Single` mode, Healthy/Provisioned, 100% traffic, one replica, with the exact candidate digest.
Public portfolio health returned 200. Its replica emitted the exact catalog tuple at
`2026-09-09T08:27:09.4726179Z`; the bounded error result stayed empty through
`2026-09-09T08:38:31.9730193Z`.

## One controlled authenticated no-op PUT

At `2026-09-09T08:38:08.0181210Z`, the existing authenticated E2E identity made exactly one
`PUT /api/portfolio/holdings`. The request used frozen integer `expectedVersion: 0` and the full
current 159 holdings with decimal-string quantities. Login, before-GET, PUT, and after-GET each
returned 200. There were no retries or redirects.

The portfolio id, `createdAt`, `updatedAt`, version (`0/0/0`), and holding count (159) remained
unchanged. The API persistent representation hash was
`283473d8da6987351cb7045e4cfdb70f96929b5ac39e4abd0ab3c1bfcf0b51e1` before, on the PUT response,
and after. Full database tuples, including holding IDs and cost-basis fields, were byte-identical:
`10d82a480c1609466a517a76219cdd482856b36bc3c8854815c0137865f04a84` before and after. The result
is `SAME_STATE`.

Two earlier local harness stops are disclosed: a PowerShell PID-variable collision and an omitted
JDBC port parsed as `-1`. Each stopped before the PUT (`putAttemptCount=0`) and made no production
mutation.

## Final state and limit

| Target | Revision / digest | Result |
|---|---|---|
| api-gateway | `0000078` / `79a3f253…` | latest-ready, 100% |
| portfolio-service | `0000096` / `1cf372a3…` | latest-ready, 100% exact candidate |
| market-data-service | `0000080` / `ad61144b…` | latest-ready, 100% |
| insight-service | `0000080` / `f7db159d…` | latest-ready, 100% |

The refresh job remains on `ad61144b…`, schedule `0 8 * * *`, retry limit 0, timeout 600 seconds.
Final Kafka is both groups `26760/26760` lag 0 with DLT 80. Final DB state is 12 users, 12
portfolios, 318 holdings, and zero violations/bad holdings/null state/legacy rows/repair functions.

This is a sanitized evidence consolidation; it omits tokens, secrets, credential-bearing URLs, and
raw customer payloads. The machine-readable companion is
[`task-7-9-serving-proof-20260909.json`](../evidence/b1-r-c/task-7-9-serving-proof-20260909.json).
