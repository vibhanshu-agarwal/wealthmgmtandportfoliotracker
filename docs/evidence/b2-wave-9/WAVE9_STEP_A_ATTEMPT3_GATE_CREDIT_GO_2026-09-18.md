# Wave 9 Step A — Attempt 3 gate-credit evidence

**Decision:** **GO — Wave 9 Step A is complete for gate credit.** This owner-operated, pre-exposure
backend-route verification does not enable either production flag, authorize a build or deployment,
or complete Wave 10.2 Step B / production exposure.

## Authority and timing

The owner authorized one Attempt 3 by Vibhanshu at `2026-09-18T13:06:58Z`, with latest permitted
attempt-start UTC `2026-09-18T13:36:58Z`. The controlling activation record is wrapper probe 1:

```text
probe 1/6 started-utc=2026-09-18T13:11:19.8478851Z curl-exit=0 http=503
```

It began 25 minutes 38.1521149 seconds before the deadline. Probe 2 returned HTTP `200` at
`2026-09-18T13:12:13.2586731Z`; no further activation probe was issued. The earlier authorization
and its Attempt 2 STOP/401 result are separate, consumed history and do not govern Attempt 3.

## Evidence integrity

| Artifact | Location / handling | Size | SHA-256 |
|---|---|---:|---|
| Step A result | Owner-controlled out-of-repository original; reviewed directly | 2,769 bytes | `034B2E3058E7CAB622031033DD27AD3CA2DA76B77ABB9F5B4601ACD172D82C9F` |
| Operator transcript | Owner-controlled out-of-repository original; reviewed directly | — | `E7EC254F0F1097575248D77CC9FC6B9838ED60819855274CF48C178EC6129B7F` |
| Preflight JSON | Owner-controlled out-of-repository original; not imported | 10,194 bytes | `55ACE23DFD0CB330142A225D09E5891305533287E4C1F779CB80A26EBCAE82FC` |

The transcript contains no credential value; the interactive prompt was masked. This record retains
only the gate-relevant, sanitized probe facts and does not reproduce operator identity, local paths,
subscription identifiers, or command input.

## Result

The result used baseline `main@94f9a20bf828fa7bfde47b581b3a818555c1fb87` and completed all nine
recorded operations with HTTP `200`:

- login completed in `76.384` seconds, within the `225`-second timeout;
- the deliberate non-golden composition write advanced version `8` to `9` for `159` holdings;
- the first demo-reset attempt returned `200`, advancing to version `10`;
- post-reset and post-cleanup reads both matched the exact golden state; and
- mandatory cleanup was armed and its reset returned `200`.

All four boolean golden-state assertions in the immutable JSON are `true`. The serving gateway was
attested as `api-gateway--0000081` at
`sha256:090ad3ba4b7ada20bca71781d4a1ce3ac81029b8a23a8a1d180dce2cff540d09`.

## Gate effect and retained boundaries

This closes the **Wave 10.2 Go-action Step A** technical gate. It does not reopen or alter B2 Task
8.9, which was already COMPLETE / GO on its own evidence. The result JSON deliberately retains
`condition_5_status: open_owner_question`; this filing does not answer that question, set either
flag, dispatch a workflow, deploy a frontend, expose the Asset Picker, or perform Step B's browser
verification. Those remain separate owner decisions.
