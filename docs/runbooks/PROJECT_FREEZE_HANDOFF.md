# Git-only project freeze and restart handoff

**Completed publication scope — historical:** on 2026-09-27 UTC the owner authorized publishing
this restart package and merging with a merge commit once all applicable CI checks are green:
"Publish the restart-kit bundle and merge with a merge commit once all applicable CI checks
are green. No deployment or live operations." The restart kit subsequently merged through #335
at `66e5064f`. This historical approval is not authority for live access, credentials, cloud
reads, dispatch, deployment, rotation, shutdown or cleanup.

**Current checked source:** 2026-09-28 UTC, `main@59af233ee0fcea7c07a95b528418ed665401e69b`
(PR #336), after the restart kit merged through #335 and the media-package status through #336.
This task inspects local source and retained sanitized status; it makes no fresh endpoint, Azure,
database, secret or billing read. The owner requires restart from merged Git: no loose worktree
documents, unpublished branches, chat history or agent memory may be necessary dependencies.

## 1. What is accepted, and what is not

The desktop demo's accepted verdict is **PASS_WITH_EXPECTED_DEFECTS**. Final-build rehearsal,
targeted checks, multi-user suite and temporary-account cleanup have distinct evidence/limits
in the [demo preparation plan](../plans/ASSET_PICKER_DEMO_PREPARATION_PLAN.md) and
[sanitized A4 record](../evidence/phase3-a4/A4_VERDICT_RECORD_runs-1-3.md).
The source audit records 33 backlog items: **11 fixed, 2 superseded, 20 open** at this cut.
Use [each backlog disposition](../todos/backlog/README.md), not historical pending prose,
when choosing work later.

Both public price-write and advisor IDOR routes were removed, deployed and own-scope
live-checked. This is not exhaustive security certification or proof that historical prices
were never changed. Gate E was skipped by the owner, not performed. Header regression proof
is merged test-only delivery. The advisor code cleanup is merged **but not deployed**.

Still-open advisor follow-ups are the active-sentiment replacements for deleted live smoke
tests, inert portfolio-URL configuration in Compose/parked AWS, and a separate decision on
the retained seed route/cache and dependent E2E setup. See the
[advisor disposition](../todos/backlog/portfolio-advisor-cross-user-authorization/README.md).
They do not reopen the closed IDOR or silently become required demo fixes.

Historical serving basis, not a current Azure inventory:

| Component | Accepted recorded basis | Limits |
|---|---|---|
| Frontend | #320 build `brJCAsidY8FAIgtIzNtSo` | Observed during the accepted demo checks; not re-probed by this task. |
| Gateway / portfolio | #320 deploy records; gateway revision `--0000081`, portfolio `--0000098` | Later scoped deploy comparisons reported these unchanged; selected-field snapshots are not full configuration attestation. |
| Market-data and refresh Job | #327 merge `9c733f6d`; deploy run [36259687567](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/36259687567); app `--0000082`, digest prefix `48a649c0` | One non-numeric POST returned 404; a price read succeeded. No retrospective price audit. |
| Insight | #329 merge `6a82f3da`; deploy run [36285996570](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/36285996570), attempt 1; `--0000082`, digest prefix `aa1e9e3c` | One own-ID analyze request returned 404 and 158 summaries were well-formed. GitHub binding cannot exclude out-of-band Azure changes. |
| Advisor cleanup | #332 merge `bd1c325f` | Source only; the operational basis remains the route-removal deploy, not a deployed cleanup. |
| Header proof | #331 merge `d439d3a2` | Regression/mutation tests, not a runtime change or universal security proof. |

Prefixes above are navigation aids, not executable digest pins. For a future release obtain
the full current SHA/digest and a fresh reviewed packet. No historical hash or revision is
a safe rollback command.

## 2. Required artifacts now in merged Git

- [Operator guide](DEMO_OPERATOR.md): secure sign-in, warm-up, baseline hard stop, edit/reverse
  and exact restore. Independent review cleared the corrections at `0ad791ae`; the kit is filed
  through #335 at `66e5064f`. The guide has not been re-rehearsed.
- [Preserved kit and hashes](../../scripts/demo/README.md): warm-up, its localhost test/mock,
  signed-in snapshot reader and verifier. Helpers are unchanged; no app implementation.
- Configuration examples already tracked: [.env.example](../../.env.example) and
  [.env.secrets.example](../../.env.secrets.example). They describe inputs, not valid credentials.
- [Current operations](CURRENT_OPERATIONS.md), [observability](OBSERVABILITY.md),
  [architecture](../architecture/README.md), [E2E guides](../e2e-flows/),
  [roadmap](../../ROADMAP.md) and [restart priority matrix](../../roadmap_enhancements_v5.md).
- Accepted sanitized verdict/disposition summaries in the dashboard/backlog, with their
  recorded provenance and limitations. Needed restart facts are not left only in chat.

Raw Playwright traces/screenshots, credential files, authenticated responses and private
snapshots are not included or certified secret-free. Historical evidence hashes identify past
records but do not promise those raw files will survive. A future reader can recover recorded
decisions from Git, not rerun every historical proof from these summaries. No raw private
record must be required to operate the kit; new live verification needs fresh authorization.

## 3. Maintenance decisions before parking the project

The owner must name who owns maintenance and choose whether to retain the deployed demo.
This handoff records **unassigned decisions**, not an agreed recurring service:

| Decision | Why it remains necessary |
|---|---|
| Retain versus shut down resources, and cost owner | Scale-to-zero is not zero spend; Jobs, storage, logging and external services can continue. Shutdown/apply needs a separate reviewed operation. |
| Manual allowance audit owner/cadence | [Observability](OBSERVABILITY.md) requires manual audits at intervals no greater than 31 days. No monitor or automation was created here. Confirm actual billing/caps only under approved reads. |
| Credentials, identities and access recovery | Arrange a secure source for renewed application/cloud credentials and access; don't depend on an old local secrets file. Rotation or export is separately authorized. |
| Domain/TLS and provider/model continuity | Expiry, model availability, provider symbols and stored data can change during the pause. Choose maintenance responsibility; no current expiry or provider health is claimed here. |
| Private evidence/worktree cleanup | Inventory first, retain only needed sanitized Git records, then get target-specific cleanup approval. Expendable does not itself mean authorized deletion. |

Source schedules at this cut: the market refresh Job is `0 8 * * *` (08:00 UTC); Azure-profile
FX cache refresh is 06:00 with no explicit scheduled-method timezone. FX also depends on the
service being active. Neither schedule proves a particular run succeeded.
Current workflow monitoring is manual-dispatch, not an assumed nightly demo assurance service.

## 4. Restart from Git only

1. Clone the repository from its approved remote and check out merged main. Record the exact
   commit; read the dashboard, this handoff and the backlog before choosing work.
2. Resolve the links/artifacts above from that checkout. Run the kit's offline verification
   commands. No private worktree path, credential or live target is needed for those tests.
3. Treat prior acceptance as historical. Compare source/workflow changes; identify intended
   deployed identity and any source-only changes. Obtain scope/approval for current GitHub,
   endpoint/cloud/configuration reads when needed; do not silently equate main with serving code.
4. Re-establish credentials through approved secure inputs. Under a newly approved session,
   warm up and verify the seeded account's full baseline before any edit. A changed baseline
   means revise/review the protocol, not auto-seed or recreate deleted users.
5. Pick genuine open work from the matrix/backlog. R1 (Sharpe/Sortino) includes charts,
   explanations and AI guidance; deferred features have no implementation commitment now.
6. Build a fresh bounded packet for any deploy/repair/cleanup. Do not replay old seed/reset,
   Mongo repair, offset reset, rollback or account-cleanup commands.

**Initial candidate clean-checkout verification — 2026-09-27 UTC:** a fresh, shallow local clone of
`5b1fad269f5a892cededbe98a71774b566306349` (tree
`3bffb298cc14ebf4e3e238962ef99723f6046dab`) contained only tracked checkout files and remained
clean after checks. It required no old private kit, credential or live target.

| Offline check | Recorded result |
|---|---|
| Preserved helper SHA-256 / LF checkout | 5/5 source hashes matched; all script/fixture files retained LF |
| Relative links in the six changed Markdown files | 117 checked, zero missing targets; this was a path check, not an anchor or remote-URL check |
| Tracked configuration examples | Both `.env.example` and `.env.secrets.example` present; no actual secret file read |
| Warm-up localhost suite | 54/54 checks, zero failures, exit 0; 08:10:47–08:13:52 UTC |
| JavaScript syntax | Reader and mock passed `node --check`; reader not executed in a browser |
| Snapshot CLI smoke | Invented two-holding copy VERIFIED; same-copy diff IDENTICAL/exit 0; changed quantity rejected/exit 1 |
| Master-plan status propagation tests (`scripts/tests/test_master_plan_status_propagation.py`) | 33/33 passed; rerun: `python -B -m unittest discover -s scripts/tests -p test_master_plan_status_propagation.py` |

Those tests cover `scripts/check_master_plan_status_propagation.py`, not the separate
`scripts/classify_changed_paths.py` CI path classifier. This documentation-only follow-up
retains all five tested helper hashes; it restores the owner-sign-in protocol, presentation
fallbacks and desktop/data-upgrade guidance without authorizing any live execution.
Independent review of `1fecc1f8` held publication for two Important and six Minor findings;
the reviewer cleared all eight at `0ad791ae` on 2026-09-27 UTC, reporting a fresh full-clone
33/33 test pass. An earlier partial-extract run failed because workflow files were absent;
that was not reported as a branch defect. The sign-in rule
is scoped to this browser walkthrough, not a blanket claim about agent capabilities or
separately approved credential automation.

Runtime versions were Windows PowerShell 5.1.26100.9549, Node 26.5.0 and Python 3.14.4.
These are the observed test runtimes, not new minimum-version commitments. The subsequent
recording changes only documentation, not these tested helpers/fixtures. The full application
suite and live browser flow were not run by this task; future CI does not automatically run the
new warm-up suite. Review clearance is recorded. No live readiness is implied by these results.

**Merged-main clean-clone verification — 2026-09-28 UTC:** a separate full clone of
`main@59af233ee0fcea7c07a95b528418ed665401e69b` was clean before and after the checks. It used
only tracked files, the bundled Python runtime, Windows PowerShell, Node, a localhost mock and
synthetic fixtures. It did not read an actual secret file or contact a live/cloud target.

| Offline check | Result |
|---|---|
| Preserved helper SHA-256 and checkout attributes | 5/5 hashes matched the preserved-kit table; the five helpers and two fixtures checked out as LF |
| Restart-document relative paths | 121 local Markdown paths in the six restart documents resolved; zero missing targets. This checks paths only, not anchors or external URLs. |
| Tracked configuration examples | `.env.example` and `.env.secrets.example` present; `.env.secrets` absent from tracked files and not read |
| Warm-up localhost suite | 54/54 checks passed; exit 0; `ALL DEMO-WARMUP TESTS PASSED` |
| JavaScript syntax | Mock and reader passed `node --check`; the reader was not run in a browser |
| Snapshot CLI smoke | Same fixture: `IDENTICAL`, exit 0. Changed fixture: `CHANGED AAPL: 1.000 -> 1.001`, exit 1 as deliberately rejected. |
| Master-plan status propagation suite | 33/33 passed; `Ran 33 tests`, `OK` |

Windows PowerShell was `5.1.26100.9549`, Node was `v26.5.0`, and the bundled Python runtime was
`3.12.14`. The full application suite and a live browser flow were not run. This verification
result is ready for independent review; its documentation becomes a durable restart record when
its reviewed carrying change merges. No live readiness is implied.

## 5. Remaining freeze sequence

1. The kit was filed through #335 at `66e5064f`; #336 at `59af233e` records the completed media
   package. Independently review and file this merged-main verification record.
2. The full clean-clone offline checks above passed; retain their bounded, non-live scope.
3. Settle the maintenance decisions above.
4. The LinkedIn, resume, PPT and video package is complete and reviewed; its agreed audience,
   duration, narrative and live-versus-recorded form are recorded in the demo preparation plan.
5. Inventory remaining private artifacts/worktrees and execute only specifically approved
   cleanup. Preserve a final accurate Git status handoff before the long pause.

The existing app's technical demo gates are not reopened just by this preservation task.
The project freeze is not complete: review/filing of this record, maintenance choices and private
hygiene still need their own steps.
