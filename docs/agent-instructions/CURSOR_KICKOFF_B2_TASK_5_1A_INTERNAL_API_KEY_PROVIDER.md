# Cursor Kickoff — B2 Task 5.1a `InternalApiKeyProvider`

**Date:** 2026-09-01

**Prepared for:** Cursor (implementation)

**Baseline floor:** `main@3396ec454d9d5baa5d0a802490e71b1e330fe441`

**Required start point:** current `main` containing this handoff; record its exact SHA before branching

**Suggested branch:** `feat/b2-task-5-1a-internal-api-key-provider`

**Assignment type:** source, packaging, and CI implementation only; no deployment or production operation

---

## 0. Assignment and stop condition

Implement only B2 Task 5.1a:

1. a resolve-once `InternalApiKeyProvider` Spring component;
2. an independent, non-disclosing `InternalApiKeyPresenceProbe`;
3. a deterministic plain `probe.jar` alongside the existing `app.jar`;
4. exact Azure-image packaging for both jars;
5. a two-case `azure-image-smoke-test` CI job; and
6. fail-closed integration of that job with the shipped docs-only classifier and required
   `ci-required` aggregate.

Stop with a source-only PR open for senior review. Do not merge on your own authority.

This assignment does **not** authorize:

- Task 5.1 `DemoResetAuthorizationFilter`;
- Task 5.1b `ReplicaTokenProvider`, `ReplicaTokenFormula`, `ReplicaTokenTool`,
  `replicaTokenJar`, or `/replica-token.jar`;
- Tasks 5.2–5.6 or any Wave 8 consumer;
- changes to routes, authentication, read-only enforcement, or public APIs;
- adding `azure-image-smoke-test` as a separate branch-protection context;
- any Azure build, deployment, Container App execution, secret read, or production probe;
- changing B1 Task 5.7/G5 or starting B1 Waves 6–7; or
- enabling either Asset Picker feature flag.

The PR is complete only when the provider/probe contracts, Gradle artifacts, Azure image,
two smoke cases, and aggregate result matrix are all verified. A green Java unit test without the
real image smoke job is incomplete. A green smoke job outside `ci-required` is also incomplete.

## 1. Read these files before editing

Read current `main`, not cached excerpts or line numbers in this handoff:

1. [`docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md`](../plans/ASSET_PICKER_E2E_MASTER_PLAN.md),
   especially “Selected priority and remaining lanes.”
2. [`.kiro/specs/asset-picker-composition/requirements.md`](../../.kiro/specs/asset-picker-composition/requirements.md),
   requirements 7.3 and 7.3a.
3. [`.kiro/specs/asset-picker-composition/design.md`](../../.kiro/specs/asset-picker-composition/design.md),
   design D5.
4. [`.kiro/specs/asset-picker-composition/tasks.md`](../../.kiro/specs/asset-picker-composition/tasks.md),
   GC.10, Task 5.1a, Task 5.1b’s explicit dependency on 5.1a, and gate 5.6.
5. [`api-gateway/build.gradle`](../../api-gateway/build.gradle).
6. [`api-gateway/Dockerfile.azure`](../../api-gateway/Dockerfile.azure). Leave the AWS
   [`api-gateway/Dockerfile`](../../api-gateway/Dockerfile) untouched.
7. [`.github/workflows/ci-verification.yml`](../../.github/workflows/ci-verification.yml),
   especially `changes`, `unit-tests`, and `ci-required`.
8. [`scripts/tests/test_classify_changed_paths.py`](../../scripts/tests/test_classify_changed_paths.py),
   which executes the aggregate’s real shell/JQ contract.
9. [`api-gateway/src/main/java/com/wealth/gateway/CloudFrontOriginVerifyFilter.java`](../../api-gateway/src/main/java/com/wealth/gateway/CloudFrontOriginVerifyFilter.java)
   only as a resolve-once precedent. Do not refactor it; Task 8.2a owns that work.

If `main` has advanced, re-read the diff and stop if another branch has changed Task 5.1a,
`Dockerfile.azure`, the docs-only topology, or the aggregate contract.

## 2. Frozen architecture decisions

### 2.1 Task 5.1a and Task 5.1b remain separate

Task 5.1a ships `app.jar`, `probe.jar`, and two probe smoke cases. Task 5.1b depends on 5.1a and
later extends the same Gradle/Docker/CI mechanism with `replica-token.jar` and the third smoke case.

Do not make Task 5.1a depend on Task 5.1b, and do not implement a conditional or placeholder third
case. The Task 5.1a job must be green with exactly the two presence-probe cases it owns.

### 2.2 Preserve the docs-only graph by dependency propagation

Add `azure-image-smoke-test` as a direct child of `unit-tests`:

```yaml
  azure-image-smoke-test:
    needs: unit-tests
```

It carries no job-level `if:` and no `always()`. Therefore:

- a docs-only PR skips `unit-tests`, and this job skips by `needs` propagation;
- a code PR runs it after unit tests, in parallel with the existing integration-test chain; and
- a unit-test failure prevents spending Azure-image build minutes.

This preserves `unit-tests` as the graph’s only docs-only skip condition. Do not add a second path
classifier or a direct `needs: changes` condition to the new job.

### 2.3 Make the image job transitively required

Add `azure-image-smoke-test` to `ci-required.needs` and to the aggregate’s exact expected map:

- expected `skipped` when `docs_only=true`;
- expected `success` when `docs_only=false`.

Do not add it directly to branch protection. The already-required `ci-required` context makes it
transitively required while preserving one stable branch-protection gate.

Any missing result, unexpected skip/run, failure, cancellation, timeout, neutral result, action
required result, or future unknown conclusion must still fail the aggregate.

### 2.4 Never disclose the key

Neither the provider nor the probe may log, print, hash, measure, or otherwise expose the
`INTERNAL_API_KEY`. The probe emits only `blank\n` or `nonblank\n` on stdout and emits nothing to
stderr on success.

## 3. Interfaces and files

### 3.1 `InternalApiKeyProvider`

**Create:**

- `api-gateway/src/main/java/com/wealth/gateway/InternalApiKeyProvider.java`
- `api-gateway/src/test/java/com/wealth/gateway/InternalApiKeyProviderTest.java`

Required shape:

```java
@Component
public final class InternalApiKeyProvider {
    private final String value;

    public InternalApiKeyProvider() {
        this(System.getenv("INTERNAL_API_KEY"));
    }

    InternalApiKeyProvider(String value) {
        this.value = value;
    }

    String value() {
        return value;
    }

    boolean isConfigured() {
        return value != null && !value.isBlank();
    }
}
```

The environment read occurs exactly once in the production constructor. The package-visible value
constructor is the test seam and later lets consumers inject a provider double without mutating the
process environment. Preserve the raw resolved string; `isConfigured()` alone decides whether it
is usable.

Unit cases: `null`, `""`, ASCII whitespace, Unicode whitespace accepted by `String.isBlank()`, and
a non-blank value. Assert the value is returned unchanged and the configured predicate is exact.

### 3.2 Independent presence probe

**Create:**

- `api-gateway/src/main/java/com/wealth/gateway/InternalApiKeyPresenceProbe.java`
- `api-gateway/src/test/java/com/wealth/gateway/InternalApiKeyPresenceProbeTest.java`

Required responsibilities:

```java
static String classify(String value) {
    return value == null || value.isBlank() ? "blank" : "nonblank";
}

public static void main(String[] args) {
    System.out.print(classify(System.getenv("INTERNAL_API_KEY")) + "\n");
}
```

The probe must not call, import, or share implementation with `InternalApiKeyProvider`. Its value is
an independent second environment read using the same JDK predicate.

Test at two levels:

1. direct classification for null, empty, ASCII whitespace, Unicode whitespace, and non-blank;
2. a real child JVM whose environment explicitly removes or sets `INTERNAL_API_KEY`, asserting
   exact stdout, byte-empty stderr, and exit code zero for unset, empty, whitespace, and non-blank.

Use `System.out.print(... + "\n")`, not `println`, so the executable contract does not vary to
Windows `\r\n`.

### 3.3 Deterministic Gradle artifacts

**Modify:** `api-gateway/build.gradle`.

- Configure `bootJar.archiveFileName = 'app.jar'`.
- Register a plain `Jar` task named `probeJar`.
- Configure `probeJar.archiveFileName = 'probe.jar'`.
- Include only `InternalApiKeyPresenceProbe.class` and its `Main-Class` manifest.
- Depend on compiled main classes, not on `bootJar` internals.
- Do not add a runtime dependency to the probe jar.
- Do not create `replicaTokenJar`; Task 5.1b owns it.

After `:api-gateway:bootJar :api-gateway:probeJar`, `api-gateway/build/libs/` must contain the two
fixed filenames used by the Dockerfile. No wildcard-based contract is acceptable.

### 3.4 Azure Docker image

**Modify:** `api-gateway/Dockerfile.azure`.

Builder command:

```dockerfile
RUN chmod +x gradlew \
    && ./gradlew :api-gateway:bootJar :api-gateway:probeJar --no-daemon
```

Runtime copies:

```dockerfile
COPY --from=builder /workspace/api-gateway/build/libs/app.jar /app.jar
COPY --from=builder /workspace/api-gateway/build/libs/probe.jar /probe.jar
```

Keep `ENTRYPOINT ["java", "-jar", "/app.jar"]` unchanged. Do not touch the AWS Dockerfile, add
shell tooling, start the gateway for the smoke test, or copy jars through `*.jar`.

### 3.5 CI job and aggregate contract

**Modify:**

- `.github/workflows/ci-verification.yml`
- `scripts/tests/test_classify_changed_paths.py`

The job checks out the repository, builds the real Azure image from repository root, and runs two
self-contained containers:

```bash
docker build -f api-gateway/Dockerfile.azure -t probe-smoke-test .
docker run --rm --entrypoint java -e INTERNAL_API_KEY= probe-smoke-test -jar /probe.jar
docker run --rm --entrypoint java -e INTERNAL_API_KEY=smoke-test-value probe-smoke-test -jar /probe.jar
```

Capture stdout and stderr separately for each container. Require exact stdout `blank\n` and
`nonblank\n`, byte-empty stderr, and exit code zero. Do not print the supplied value. `--rm` is the
entire lifecycle; do not run a background gateway container.

Update the real aggregate tests rather than adding a Python-only copy:

- include `azure-image-smoke-test` in the workflow-structure dependency assertions;
- assert it needs only `unit-tests` and has no job-level condition;
- include it in `ALL_JOBS` and the docs-only `CHAIN_JOBS`;
- require `skipped` for it in the legitimate docs-only shape and `success` in the full-suite shape;
- retain all malformed-value, missing-job, unexpected-result, and evidence-sink tests; and
- update the human-readable evidence text so its expected skip list names the new job.

The implementation PR itself changes Java, Docker, workflow, and tests, so the classifier must emit
`docs_only=false`; all eight aggregate dependencies, including the new image job, must succeed.

## 4. Test-driven implementation sequence

1. Record the exact `main` SHA and confirm a clean worktree.
2. Add failing provider tests; run only `InternalApiKeyProviderTest` and confirm failure because the
   class does not exist.
3. Implement the provider minimally; rerun the focused test.
4. Add failing probe classification and child-process tests; confirm the probe is absent.
5. Implement the probe; rerun the focused test.
6. Add Gradle artifact assertions or focused build verification before registering `probeJar`.
7. Register deterministic `app.jar`/`probe.jar` tasks; build both and inspect exact filenames and
   manifests.
8. Change `Dockerfile.azure` to exact builds/copies; build the real image and run both probe cases.
9. Add failing workflow/aggregate contract tests for the eighth dependency and propagated skip.
10. Add the job and aggregate map entry; rerun the real extracted-shell/JQ tests.
11. Run the complete verification set below.
12. Update this ledger and the master plan as **implemented but unmerged**; keep Task 5.1a
    unchecked until post-merge evidence exists.
13. Open a source-only PR and stop for senior review.

## 5. Required local verification

Run from repository root:

```bash
./gradlew :api-gateway:test --tests '*InternalApiKeyProviderTest' --tests '*InternalApiKeyPresenceProbeTest'
./gradlew :api-gateway:test
./gradlew :api-gateway:bootJar :api-gateway:probeJar
docker build -f api-gateway/Dockerfile.azure -t probe-smoke-test .
docker run --rm --entrypoint java -e INTERNAL_API_KEY= probe-smoke-test -jar /probe.jar
docker run --rm --entrypoint java -e INTERNAL_API_KEY=smoke-test-value probe-smoke-test -jar /probe.jar
python scripts/tests/test_classify_changed_paths.py -v
python scripts/tests/test_master_plan_status_propagation.py -v
```

Run the repository-pinned Actionlint invocation used by CI. Do not download an unpinned replacement.

Before pushing, verify:

- `git diff --check` is clean;
- only Task 5.1a files and governed status documentation changed;
- neither `replica-token.jar` nor Task 5.1b classes appear in the diff;
- the AWS Dockerfile is unchanged;
- no secret value appears in source, logs, fixtures, or snapshots; and
- no task checkbox is prematurely checked.

## 6. Live CI acceptance

The PR’s `pull_request` run, not a same-SHA push run, is authoritative. Record:

- run id, attempt, event, and head SHA;
- `changes=success` with `docs_only=false`;
- all aggregate dependencies `success`, including `azure-image-smoke-test`;
- `ci-required=success` with declared-versus-observed agreement;
- the image job duration and whether it extended the existing critical path; and
- exact probe outputs without exposing the test value.

If the new job is absent from `ci-required`, unexpectedly runs on a docs-only shape in the contract
tests, uses a second job-level skip condition, or materially extends the full-suite critical path
without review, stop and request architecture review. Do not weaken the aggregate to make CI green.

## 7. Governance and PR contract

The implementation PR touches B2 and process-control surfaces. Its body must contain exactly one:

```text
Master-plan impact: updated — B2, process
```

In that PR:

- update this B2 ledger and the master plan to say Task 5.1a is implemented but unmerged;
- keep the Task 5.1a checkbox unchecked;
- do not claim deployment, live proof, user-visible behavior, or Task 5.1b completion; and
- stop with the PR open for review.

After the implementation PR merges, a docs-only reconciliation PR may mark Task 5.1a complete with
the merge SHA and accepted CI evidence. That post-merge update is not part of Cursor’s source PR
unless the repository owner explicitly assigns it later.

## 8. Cursor completion report

Report:

1. branch and commit SHA;
2. exact files changed;
3. focused and full local test results;
4. exact jar names and manifest checks;
5. both local image-smoke results;
6. Actionlint result;
7. authoritative PR-event CI run and aggregate evidence;
8. whether the new job changed the critical path;
9. confirmation that Task 5.1b, deployment, production, and feature flags were untouched; and
10. PR URL, still unmerged and awaiting senior review.
