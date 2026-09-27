# Desktop demo operator guide

**Owner approval required before live execution:** approve a bounded session, account and target
before health wakes, sign-in, chat or edits. This guide creates no accounts, grants no deployment
or recovery authority, and does not authorize cleanup. Publication/merge of this new kit also
requires approval. An unmerged copy is a candidate.

**Prepared:** 2026-09-27 UTC from the prior reviewed operator flow and merged status at
`main@cedbb5af`. This reconciled guide is **not yet independently reviewed or live-rehearsed**.
Historical acceptance and evidence limits remain in the
[demo preparation plan](../plans/ASSET_PICKER_DEMO_PREPARATION_PLAN.md). The
[kit inventory](../../scripts/demo/README.md) preserves the helpers and their original hashes.

## 1. Before starting

Use the existing seeded **writable E2E account**, not the deleted A4/post-#320 certification
users. Obtain its credentials through approved secure inputs; none are supplied here.
The reader is bound to that E2E identity. Do not substitute another account or create one
without a reviewed protocol. Ensure no other person, browser session or E2E workflow uses
the same account during the demonstration.

The showcase account is different: its read-only filter deliberately exempts holdings save
and its own demo reset. Do not describe it as unable to save, or use its reset as this
walkthrough's undo. Never click a non-demo account's visible reset control.

Before relying on old acceptance, confirm the intended frontend/build and relevant backend
deployment records under an approved preflight. The historical frontend build is
`brJCAsidY8FAIgtIzNtSo`; later security deployments changed market-data and insight separately.
Use the [restart handoff](PROJECT_FREEZE_HANDOFF.md), not the old private revision table.
A mismatch stops before editing; new code/configuration/data drift may require a new packet.

Choose a session window that avoids price/FX refresh when stable comparisons are required.
Source schedules and their runtime limits are in [current operations](CURRENT_OPERATIONS.md).
There is no arbitrary date or appointment imposed here.

## 2. Warm-up and keep-alive

From the repository root, in a dedicated PowerShell window, under the approved live session:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/demo/demo-warmup.ps1
```

Keep the default target and timing; don't override them to bypass failure. Start the walkthrough
only after GO and while the latest readiness status remains GO. All four health paths must
have answered 200 within two minutes. Readiness must be reached before the ten-minute deadline.
Keep the window open throughout: it sends health probes for 45 minutes after GO, then stops.
Ctrl+C stops it too. A later NOT READY means pause/use an explicitly labelled fallback,
not increase replicas or silently retry the session.

Health readiness is not proof that page data or Kafka consumers have caught up. Once signed
in, visit Overview, Portfolio, Market Data and AI Insights and confirm the required data has
loaded. Wakes can advance consumers; chat can call a paid model or cache an answer.

Historical comparison on 2026-09-25: Edit Holdings appeared in 2.7 seconds when warm, versus
roughly 60-second price requests on a cold visit. These are observations, not performance
guarantees. An expired keep-alive is not continuing readiness.

## 3. Step 0 — hard stop before any edit

1. Sign in securely to the approved E2E account. Keep the browser pane at a stable size and
   locate controls from a fresh page state after layout changes.
2. Execute [rehearsal-read-portfolio.js](../../scripts/demo/rehearsal-read-portfolio.js) inside
   that signed-in page using the approved browser's JavaScript evaluation facility. Do not
   execute it with Node as a live reader. Never display, copy or store the token.
3. Require `ok`, `emailMatches`, exactly **one portfolio**, **159 holdings** and **159 distinct
   tickers**, an exact quantity for each holding (`allQuantitiesExact`), and a non-null version.
   Inspect the returned quantity text; browser source support must not silently round it.
   These counts preserve the rehearsed seeded-account protocol, not a universal product rule.
   If the seeded state has changed, stop for a revised protocol; don't relax the baseline.
4. Save the full `canonical` copy privately, not just its digest: UTF-8, LF, exactly one final
   newline. Record the version separately. From the repository root run:

   ```powershell
   python -B scripts/demo/verify-snapshot.py <baseline-file> <holdingsSha256> 159
   ```

   Replace the placeholders with the private file and the digest returned by the page.
   Require VERIFIED and exit 0. This binds the copy to the reported live digest; it is not a
   saved raw API response.
5. Choose two distinct, freshly priced ordinary USD stocks already held: **Q** to change,
   **R** to remove. Note their exact baseline quantities and the approved temporary Q quantity.
   Avoid stale/unpriced holdings and tiny quantities obscured by display rounding.

If any baseline/read/copy/verification check fails, make **no edit**. Keep snapshots private;
the verifier does not replace the identity/count/version checks.

## 4. Walkthrough and exact restore

1. **Overview:** value, allocation and change. Explain partial coverage if shown.
2. **Portfolio:** holdings and performance chart. Use today's visible history; do not promise
   that old August dates remain visible or that a smooth chart certifies provider prices.
3. **Edit Holdings:** change Q and remove R. Click only inside the dialog; an outside click,
   Escape or close discards the draft. Review exactly one update and one removal, then save.
   Wait for Holdings saved and verify Q plus the resulting 158 holdings. Historical saves
   took 20–38 seconds; do not click again or close while saving.
4. **Market Data:** prices and change. Allow at least **40 seconds after save** before pointing
   to the header ticker or returning to Overview; those can lag the holdings table.
5. **AI Insights:** show the summary; card change is over the last N stored prices, not 24 hours.
   Ask at most **one** short chat question in this walkthrough. A slow/missing answer calls for
   a clearly labelled prepared example or postponement, not repeated requests. Prepared media
   must be agreed separately; this guide does not invent an answer.
   The source label attributes sentiment, possibly cached; it does not prove this request
   called the model or that all reply text was generated by it.
6. **Overview again:** only after the 40-second interval; confirm the edit is reflected.
7. **Restore before sign-out:** re-add R with its exact baseline quantity and return Q to its
   exact baseline quantity, review and save. Do not use reset as undo.
8. Run the reader again. Require the same account, one portfolio, 159 distinct holdings,
   exact quantities and a version. Save and digest-verify the second canonical copy as above,
   then run:

   ```powershell
   python -B scripts/demo/verify-snapshot.py --diff <baseline-file> <after-file>
   ```

   Require IDENTICAL and exit 0: all tickers and exact quantity strings must match. Only the
   version may differ; record it separately. Do not mistake a digest-only result for a
   recoverable full baseline.
9. Sign out after successful restoration and stop the warm-up when the session ends.

## 5. Stop rules and honest limits

Stop on a build/account mismatch, failed sign-in, unavailable Edit Holdings, lost readiness,
failed baseline, missing save confirmation, failed snapshot verification or any restore
difference. Do not automatically rerun, reset, repair or create accounts. Record the failure
and obtain a separate recovery decision if the account was changed.

The accepted verdict is PASS_WITH_EXPECTED_DEFECTS, not general production/security
certification. Keep the reset-control UX defect, token validity after logout, stale/partial
valuations, cache lag, empty-portfolio copy and desktop-only limitations visible when relevant.
Do not silently mark an unexercised condition fixed. Seven sampled non-USD table value checks
were INCONCLUSIVE and owner-accepted as non-blocking; nine sampled dialog estimates passed.
The chart symptom pass was inferred from page/code, not a saved analytics response or screenshot.
The source-labelled chat observation was not proof of a fresh model invocation.

This guide does not revive a full-suite, seed, cleanup or deployment approval. Future operation
must reassess data, model/provider availability and code/deployment drift.
