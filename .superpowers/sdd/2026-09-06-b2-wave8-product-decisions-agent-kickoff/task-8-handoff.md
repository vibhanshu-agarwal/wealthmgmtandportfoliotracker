# Task 8 publication handoff

**Owner approval callout:** Push, PR creation/merge, workflow dispatch, deployment, cloud/registry/secret access, and production probing remain blocked pending explicit owner authorization. This packet is local preparation only.

## Baseline and head

- Pinned base: `318f28592da6ab2e3bd66bc738aa68d374b180fa`
- Coordinator branch: `codex/b2-wave8-product-decisions`
- Integrated head before this packet: `db90fe744d388a62c57151c02bc051bbf888ce6d`
- Packet commit: recorded after verification.

## Commit map

- `0b2779be`, `1e34a35a`: fail-open login orchestration, strict response handling, diagnostics.
- `7d6305b1`, `db90fe74`, `c03b208d`: real-chain/race and trace-header evidence, classpath wiring.
- `f21671eb` through `2bfddf87`: offline Azure live-proof verifier and review fixes.
- `b59cf26`: Azure deployment-evidence workflow/helper foundation and 48 offline tests.

## Evidence

- Gateway: 316 unit, 193 integration, 4 real-chain, 37 insight, 37 market-data, and 208 portfolio tests passed; root `integrationTest` passed.
- Tooling: snapshot/allowlist/prebuilt suites passed (24 + 14 + 10); offline verifier and identity suites passed (62 + 24); actionlint v1.7.12 pinned checksum and shellcheck invocation passed.
- Master-plan propagation tests and `git diff --check` are required final checks for this packet.

## Truthful status and open gates

Tasks 8.1–8.7a are source-complete and independently reviewed. Task 8.8b's source foundation is complete and locally verified, but its deployment Go remains open. Tasks 8.8 and 8.9 remain unchecked: no workflow run, Azure deployment, serving revision/digest read-back, manifest comparison, or live proof exists. Both frontend flags remain off; Wave 10 exposure is not claimed. Task 5.6 owner GO and B1 Wave 7 prerequisites remain open where required.

## Exact PR body

The exact body is stored in `.wave8-pr-body.md` and contains exactly one status declaration: `Master-plan impact: updated — B2`.
