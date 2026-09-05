# Backlog: Complete B1 GC.5 Governance Review After Asset Picker Production E2E

**Status:** Open — intentionally deferred by the owner on 2026-09-05.
**Trigger:** Asset Picker functional E2E and Production E2E evidence are complete and accepted.
**Priority:** Resume after that trigger, alongside the other selected backlog items.
**Decision owners:** B1 governance/release owner, affected service owners, database/security
owner, and build/release architect.
**Source baseline:** B1 base `95fcb68dc7a47f99465354ec6d7b84137851389d`; source cut
`f17c90294fe181956bfc8b31d91dec3d04dd122a`.

## Status and sequencing decision

The immediate product priority is to complete the Asset Picker user journey and establish its
functional and Production E2E evidence. The GC.5 source-governance queue is deliberately parked
until that work is out of the way. When the trigger above is met, resume this backlog as a
review-family exercise rather than treating every report row as an independent product defect.

This deferral does not classify, approve, waive, or close any governance finding. It does not
authorize a release-candidate build or push, registry access, live database access, deployment,
production exposure, or completion-box changes. Those operations retain their separate gates.
If Production E2E requires a deployment or exposure that is currently behind a release gate, that
gate still needs its own concrete evidence package and owner authorization.

Asset Picker functional completion and GC.5 acceptance are different claims. The findings do not
by themselves show that Asset Picker is broken, but the combined release candidate remains
source-governance `BLOCKED`, `candidate_ready=false`, and R-C `NO-GO` until this backlog and the
other release-evidence obligations are resolved.

## Preserved baseline

The fresh `LOCAL_PREPARATION` scan at the source cut produced 508 findings:

| Obligation family | Count | Current state |
|---|---:|---|
| Content governance | 168 | `CONFIRMED_MATCH` |
| Path governance | 84 | `CONFIRMED_MATCH` |
| Path governance | 137 | `UNREVIEWED` |
| Writer inventory | 73 | `UNREVIEWED` |
| Writer inventory | 25 | `UNRESOLVED` |
| Writer/persistence coverage | 7 | `UNSUPPORTED` |
| Per-holding structural state | 9 | `UNREVIEWED` |
| Deployable envelopes | 4 | `UNREVIEWED` |
| R3 operational policy | 1 | `UNRESOLVED` |
| **Total** | **508** | **Blocked** |

Aggregate state: 252 `CONFIRMED_MATCH`, 223 `UNREVIEWED`, 26 `UNRESOLVED`, and
7 `UNSUPPORTED`.

Evidence identities to preserve:

- analyzer SHA-256:
  `090aeb9f77076c6ac6d98a7e3bf80255e03f638613b690f272590c2b474a159e`;
- policy SHA-256:
  `78bf8596d90761791028549db54db48db0865f103f07dd8fb84ebde1fb0f29c0`;
- fresh report SHA-256:
  `d438a2a4ca85ba60ff6d133a383f0673bc37c8531f18f45ef3a54f44a537e5e9`;
- historical-to-fresh semantic delta: one content-governance finding added, zero removed. The
  addition is the analyzer comment containing `JwtSessionIdentity`; it is review inventory rather
  than a newly introduced runtime presence feature.

The source report is a local governance-preparation artifact. It is not Task A/B build evidence,
registry evidence, exact-digest smoke evidence, serving evidence, or a release attestation.

## Review families when this backlog resumes

### 1. Straightforward disposition candidates

Review related findings in provenance and feature bundles while preserving every report subject:

- 389 path/content findings, especially the B2 frontend and presence changes that share the B1
  base-to-cut interval;
- 73 understood writer sites, grouped by migrations, aggregate writers, unrelated relational
  tables, Mongo, Redis, Kafka, and in-memory operations; and
- the analyzer-policy-test content family, including explanatory comments and fixtures.

A finding means that review is required. It does not automatically mean that the affected feature
is defective. “Already merged,” “owned by B2,” or “outside B1” is not sufficient on its own;
accepted dispositions must retain exact provenance, subject identity, and reviewed rationale.

### 2. Technical effect resolution

Resolve the 25 operations whose receiver, store, or table effect is not currently proven. Work in
bounded groups:

- request-header, logging-context, and concurrent-map removals;
- Mongo repository, Mongo options-builder, and Redis operations;
- JDBC read queries and the advisory-lock prepared statement; and
- composition `saveAndFlush` / `EntityManager.flush` operations.

An effect resolution does not necessarily clear the operation. A resolved relevant writer still
needs an accepted disposition, and governed-module rules may also require a disposition for a
resolved unrelated writer.

### 3. Unsupported analyzer coverage

The seven `UNSUPPORTED` findings cannot be cleared by ordinary dispositions under the current
contract. Prepare a bounded analyzer design or a contract-supported independent proof for:

- `GatewayAuthDataConfig`;
- `PostMigrationIntegrityAssertion`;
- `DemoPortfolioInitializer`;
- `SpecA912ProvenanceDataSource`;
- `SpecA912ProvenanceDataSourcePostProcessor`;
- `SpecA912StartupTransactionDiagnostics`; and
- `check_b2_demo_identity.py` subject `script:INSERT:3575989d`.

Do not restore the rejected general Python exception-constructor exemption. Review the diagnostic
datasource wrapper's reflective and unwrap paths, and the startup diagnostic's real
`DELETE FROM portfolios WHERE FALSE` execution surface, before proposing coverage.

### 4. Per-holding state and deployable envelopes

Review the nine structural subjects as four semantic families: persisted holding state,
composition/seed request state, valuation/analytics projections, and freshness configuration.
Derived output and global configuration must be distinguished from persisted per-holding state by
reviewed evidence rather than by naming alone.

Review all four computed deployable envelopes: `api-gateway` (69 members), `insight-service`
(86), `market-data-service` (77), and `portfolio-service` (145). The latter two non-gateway
services discovered outside the policy still require records. Envelope membership, roots,
migration subsets, and unsupported members must agree with the selected review cut before an
envelope can support other decisions.

### 5. R3 operational risk

Preserve the existing qualification: prior catalog evidence establishes risk for the inspected
database and inspected role. It does not independently prove the deployed application's current
effective database identity.

The later decision needs separately authorized evidence connecting the deployed application to
its effective database identity and privileges. Review routine ownership and execution,
membership/inheritance, direct table DML, and applicable database controls together. Revoking
`PUBLIC` execution alone is not sufficient because it does not remove the routine owner's own
privileges or direct DML capability. Do not edit an already-applied Flyway migration as a
substitute for an operational decision.

## Recommended execution order

1. Reconfirm the selected base, candidate cut, analyzer, policy, and preserved report identities.
2. Resolve or design coverage for the seven `UNSUPPORTED` subjects; keep the analyzer fail-closed.
3. Prepare the 25 effect-resolution records, beginning with memory/header/logging cases and then
   persistence and composition effects.
4. Review path, content, understood-writer, and migration subjects in evidence-bound families.
5. Review the nine per-holding subjects and four deployable envelopes against the final source cut.
6. Prepare R3's operational evidence and remediation decision under a separate owner-authorized
   live-operation package if live access is needed.
7. Only with explicit owner authorization, author governed records and rerun the analyzer. Reconcile
   every added, removed, and changed finding before claiming source-governance `PASS`.

## Acceptance

This backlog is complete only when:

- every confirmed, unreviewed, and unresolved source subject has an accepted contract-valid
  resolution or disposition;
- every unsupported subject has supported coverage or contract-valid independent proof;
- per-holding baselines and all four envelope records are reviewed and valid for the chosen cut;
- R3 has an accepted decision based on correctly qualified operational identity and privilege
  evidence;
- a fresh scan is reproducible, all semantic delta is explained, source governance reports `PASS`,
