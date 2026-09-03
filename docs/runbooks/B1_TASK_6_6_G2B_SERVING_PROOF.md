# B1 Task 6.6 — G2b serving proof, 2026-09-03

**OWNER DECISION — Task 6.7 R-B3 GO and local ledger closure:** Codex recommends accepting
Task 6.6 and recording Task 6.7 GO based on the completed proof below. Approval permits those
two local completion ticks; withholding approval leaves both unchecked and the R-B3 gate open.
The approved candidate is already serving. No further production action, rollback, publication,
Wave 7 activation or Writer_Convergence closure follows automatically from this recommendation.

**Verdict:** Task 6.6 technical ACCEPT. One approved deployment and one seed attempt completed.
**Execution:** 2026-09-03; deployment finished 05:26:31 UTC; seed/data proof 05:33:47–05:35:19 UTC.
**Evidence:** [sanitized machine-readable record](../evidence/b1-task-6-6/g2b-serving-proof-20260903.json).
**Protocol:** [approved execution packet](B1_TASK_6_6_G2B_EXECUTION_PACKET.md).
Raw database and portfolio captures remain private outside Git. No application code changed.

## 1. Approval and artifact binding

The owner's earlier “Please proceed” approved the exact deployment/preflight/one-seed bundle.
The owner subsequently approved GitHub's production gate; authorize-production succeeded at
05:24:08 UTC. Codex did not self-approve or bypass it. The existing shared .env.secrets supplied
all required inputs; no new credentials, grants or configuration were required. Values were used
in memory and child-process environment only; the source file was unchanged.

| Binding | Recorded value |
|---|---|
| Source | 6a171558a0f802eadd5d7ed5bf28545ca5c91905 |
| Tree | 4df697ed7605104a304ad08651e21522e32d52db |
| Single ACR build | cu4 |
| Serving image | wealthprodacr.azurecr.io/portfolio-service@sha256:2be727eaf4577699c783ae66073670d4984fe66c666af3e56422c934fdd0b023 |
| Workflow main | 9c2ebc1233801253a3e54b6e930e28e1a00ebf3d |
| Deployment | [33718062217](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33718062217), digest mode, portfolio-service only |
| Packet SHA-256 at execution | b2943b47a84f78109e54b5f8fa63016271b47a05c65ed5fa2a47bfa0eba3549f |
| Offline reference SHA-256 | 344fa0f6f657f5e16e8275d1300043f27e53ccf6bd72193c85bfb70fb99910bd |
| Catalog SHA-256 | cfa5e6b7317e922c07452359b851e55ee0a2a5ae9014224665244f9c2264de8b |
| Cost-basis anchor | 2020-01-01T00:00:00.000Z |

Validation, authorization, routing, Azure preflight, portfolio deployment and scoped
non-interference all succeeded. ACR login/build/push, refresh-job update, frontend, seed, verify
and AWS were skipped as required. There was no second build or deployment dispatch.

## 2. Serving and readiness proof

Portfolio revision 0000094 was Healthy/Running, latest and latest-ready, with the exact cu4 digest
and 100% main traffic in Single mode. All active and traffic-addressable revisions were enumerated;
the ingress had no revision-label route. Old revision 0000093 briefly deprovisioned during rollout,
then became inactive/stopped with zero replicas and zero traffic before the seed.

The pre-proof ready replica was portfolio-service--0000094-5b6c6cd557-kl6dg; the final ready
replica was portfolio-service--0000094-5b6c6cd557-tq2fv. Both had zero restarts. This records
replica replacement, not continuous service by one replica. Both observations bind to the same
sole candidate revision; the old image did not re-enter the serving set.

Startup seed and transaction diagnostics stayed false. Internal ingress, TLS policy, port 8080,
scale configuration (minReplicas null, maxReplicas 3), command/args absence and volumes were
unchanged. The anchor override and additional configuration-source environment names were absent.

The first bounded authenticated read returned 504; the second returned 200. The verifier then
raised AttributeError because it expected a top-level object. Inspection of PortfolioController
and its tests confirmed the existing list response contract. A read-only diagnostic and corrected
read confirmed exactly one E2E portfolio, valid ISO timestamps and 159 holdings. This was an
operator verifier defect, not application schema drift. All readiness versions were discarded.

Gateway 0000077, market-data 0000079 and insight 0000079 retained their preflight images,
revisions, ingress, scale and traffic. Peer image tags remain tags, not digest attestations.
The refresh-job image and 08:00 UTC schedule were unchanged; its latest execution remained
September 2's successful run. No deployment or synthetic run overlap was observed.

## 3. Fresh before, one seed, fresh after

A new authenticated eligibility read identified E2E user
00000000-0000-0000-0000-000000000e2e and portfolio
d61870f5-d420-4947-987c-401e36d2069f. Its version was frozen at **N = 0**.
The BEFORE transaction ran 05:33:48.997713–05:33:52.636185 UTC, then closed before the request.
Its complete parent and projected holdings agreed with HTTP. Every persisted holding tuple
matched the independently derived 159-ACTIVE-entry reference, so **SAME_STATE** was classified
before transmission.

An exclusive attempt marker was written before the single POST to /api/internal/portfolio/seed.
The only body field was expectedVersion: 0. There were zero retries, no redirect following,
no JWT or identity override, and no alternate seed/reset call.
Trace ID: be76c56f6c6542fdb864d770be4d3baf.

The response at 05:35:13.693197 UTC was **HTTP 200**, with the expected E2E userId,
the same portfolioId and holdingsInserted = 159. This response does not contain version or
updatedAt, and holdingsInserted is not evidence that rows were inserted.

AFTER used a separate READ ONLY transaction at 05:35:13.707203–05:35:17.853407 UTC.
One authenticated post-read returned 200 and agreed with the resulting database state.

| Complete capture | BEFORE rows | AFTER rows | Exact bytes |
|---|---:|---:|---|
| Schema metadata | 23 | 23 | Identical |
| E2E portfolio | 1 | 1 | Identical |
| E2E holdings | 159 | 159 | Identical |
| Demo portfolio | 1 | 1 | Identical |
| Demo holdings | 159 | 159 | Identical |
| market_prices | 160 | 160 | Identical |
| market_price_history | 16,284 | 16,284 | Identical |

Every file's full SHA-256, ordered header, row count and transaction timestamps are in the JSON
evidence. Both transactions confirmed read-only mode; TLS verify-full was retained. All rows and
columns of both price tables were included, without ticker filters or row limits.

The portfolio id and created_at were preserved. **Version remained 0** and updated_at remained
2026-08-31 16:02:21.975238 UTC. Complete child rows, including UUIDs and all cost-basis fields,
were unchanged and still matched the reference. Demo data and schema were byte-identical.

Price/history data had changed between the earlier 05:08 preflight rehearsal and this fresh
BEFORE capture (history grew from 16,125 to 16,284 rows). The cause was not attributed or
investigated as part of this bundle. The proof is complete byte equality across the actual
BEFORE/AFTER seed window, not a claim of no price updates throughout the whole release.

## 4. Review conclusion and stop boundary

The approved same-state outcome passes Requirements 8.17/8.18: one controlled request preserved
identity, version, timestamps and complete holdings while leaving prices, history and demo data
unchanged. Homogeneous serving bindings and final peer/configuration checks also passed.

The strict missing-version, delegation and race properties rely on the accepted source/controller
and integration tests bound to this exact image. No extra live missing-version, conflict, race
or transition probe was sent, and historical application test suites were not rerun for this proof.

**Counts:** one deployment; one seed; zero seed retries; zero rollbacks; zero additional builds.
The pre-seed conditional rollback authority ended when the attempt marker was written.
Any later production change requires a new explicit owner decision; no automatic rollback or
data repair is authorized by this result.

Task 6.6 is technically ACCEPT and recommended for local completion. Task 6.7 requires the
owner's separate R-B3 decision. Their checkboxes remain unchanged pending that decision.
The local cut tag and documentation are unpublished; aggregate AM.1/AM.2, Wave 7 activation,
B2 owner gates and Writer_Convergence remain open.
