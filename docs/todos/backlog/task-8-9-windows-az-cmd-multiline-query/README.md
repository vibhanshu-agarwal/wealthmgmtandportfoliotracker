# Task 8.9 Windows `az.cmd` multiline-query transport defect

## Status

Open implementation backlog. This record does not authorize code changes or any Production query.

## Defect

On Windows, `scripts/verify_demo_reset_azure.py` resolves `az` to the installed `az.cmd` shim and
passes the shim an argv list containing multiline KQL. The shim expands `%*` inside a parenthesized
batch block. At that boundary, the first newline terminates the Azure CLI invocation: only the first
KQL line reaches the CLI, while the remaining predicates and the trailing `--timespan ... -o json`
arguments are interpreted outside the intended invocation. The verifier's recorded `operations.argv`
shows the intended Python argv, not the argv that survived the batch boundary.

The 2026-09-15 Production verifier therefore did not issue the recorded filtered, bounded query.
Its later UTF-8 decoder exception is a downstream symptom of reading output from the malformed
invocation, not the initiating cause.

## Offline reproduction

The repository already contains a no-network `scripts/tests/stub_az.cmd` that preserves the relevant
shape of the installed shim: `%*` is expanded inside an `IF (...)` block. Invoke that stub from Python
`subprocess.run` with this argv shape:

```text
stub_az.cmd monitor log-analytics query --workspace workspace-id
  --analytics-query "ContainerAppConsoleLogs_CL\n| where ...\n| project ..."
  --timespan 2026-09-15T01:02:27Z/2026-09-15T01:05:51Z -o json
```

Observed result on Windows: the child-visible invocation ends after
`--analytics-query "ContainerAppConsoleLogs_CL`; neither `--timespan` nor `-o json` survives in that
invocation. The installed shim used by the verifier has the same load-bearing form:

```bat
@IF EXIST "%~dp0\..\python.exe" (
  "%~dp0\..\python.exe" -IBm azure.cli %*
)
```

## Required implementation and tests

- Avoid passing multiline KQL directly through a `.cmd` argv boundary. Use a transport whose
  argument semantics are explicit and tested on Windows.
- Add a Windows regression test that exercises the real `_default_command_runner` path through a
  `.cmd` shim with multiline KQL and asserts the full query, `--timespan`, and `-o json` reach one
  child invocation unchanged.
- Make the runner fail closed if the query process raises a decoding error; preserve raw bytes or
  decode in a way that cannot turn malformed transport into ambiguous event evidence.
- Record the child-visible query coordinates and raw query response required by Task 8.9, not only
  the intended pre-dispatch argv.
- Keep the existing raw 2026-09-15 proof immutable; a fixed verifier cannot retrospectively change
  its NON-GO verdict.

## Acceptance boundary

Source tests and independent review may close this implementation backlog. Task 8.9 itself remains
OPEN / NON-GO until the authorized historical query is rerun with complete raw capture and that
evidence is independently accepted.
