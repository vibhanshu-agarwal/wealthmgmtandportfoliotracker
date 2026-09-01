# B2 Task 5.1b Replica Token Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this
> plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Do not dispatch work into a
> second worktree unless the repository owner authorizes it.

**Goal:** Implement the single shared, non-disclosing replica-token source used later by B2 Waves 5
and 8, package its operator tool in the Azure api-gateway image, and prove that packaging in CI.

**Architecture:** `ReplicaTokenProvider` reads `CONTAINER_APP_REPLICA_NAME` once and delegates all
normalization and hashing to `ReplicaTokenFormula`. The provider exposes only a blank sentinel or a
12-character lowercase SHA-256 token; it never exposes the raw Azure replica name. A separate
`ReplicaTokenTool` calls the same formula for authorized operator correlation and ships as a plain,
dependency-free `replica-token.jar` beside the existing `app.jar` and `probe.jar`.

**Tech Stack:** Java 21, Spring Boot component model, JUnit 5, AssertJ, Gradle `Jar`, Docker,
GitHub Actions, Bash, and Python `unittest` workflow-contract tests.

**Spec:** `.kiro/specs/asset-picker-composition/tasks.md`, Task 5.1b (requirements 7.3a and 7.3c;
design D5).

## Global Constraints

- **Prepared for:** Cursor (implementation).
- **Date:** 2026-09-01.
- **Baseline floor:** `main@0818856c2a8c0cf7dce248da92d70ea7645e0140`.
- **Required start point:** current `main` containing this handoff; record its exact SHA before
  branching and stop if the Task 5.1a artifacts or Task 5.1b contract have changed.
- **Suggested branch:** `feat/b2-task-5-1b-replica-token-provider`.
- **Assigned worktree:**
  `D:\Projects\Development\Java\Spring\wealthmgmtandportfoliotracker-cursor`.
  Confirm it with `git rev-parse --show-toplevel` before any mutation. Do not use a nested
  `.claude/worktrees`, `.worktrees`, or repository-local `worktrees` directory.
- Source, packaging, tests, CI, and governed status documentation only. No deployment, live probe,
  Azure mutation, secret read, or production operation.
- Task 5.1a is the required predecessor and is already merged. Preserve its provider, presence
  probe, two smoke cases, and `ci-required` topology.
- Do not implement Task 5.1, Tasks 5.2–5.6, Task 8.7, or any other consumer.
- Never publish, log, return, or persist the raw `CONTAINER_APP_REPLICA_NAME`.
- Null and every `String.isBlank()` value map to the canonical empty token `""`; blank is never
  hashed. A non-blank value is hashed byte-for-byte without trimming or case conversion.
- The only accepted non-blank result is the first 12 lowercase hexadecimal characters of SHA-256
  over the raw value's UTF-8 bytes.
- The fixed vector everywhere is
  `api-gateway--0000000-abcdefg` -> `95ca17821ade`.
- The successful tool contract is exact: token plus literal `\n` on stdout, byte-empty stderr,
  and exit code `0`. Use `System.out.print(token + "\n")`, never `println`.
- Stop with a source-only PR open for senior review. Do not merge on Cursor's authority.

---

## 0. Read and re-verify before editing

- [ ] Read current `main` versions of:

  1. `AGENTS.md`;
  2. `docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md`, especially the selected priority;
  3. `.kiro/specs/asset-picker-composition/requirements.md`, requirements 7.3a and 7.3c;
  4. `.kiro/specs/asset-picker-composition/design.md`, D5;
  5. `.kiro/specs/asset-picker-composition/tasks.md`, GC.10, Tasks 5.1a/5.1b, and gate 5.6;
  6. `InternalApiKeyProvider.java` and its test as the resolve-once component precedent;
  7. `InternalApiKeyPresenceProbeTest.java` as the child-process executable-test precedent;
  8. `api-gateway/build.gradle`;
  9. `api-gateway/Dockerfile.azure`;
  10. `.github/workflows/ci-verification.yml`, especially `azure-image-smoke-test` and
      `ci-required`; and
  11. `scripts/tests/test_classify_changed_paths.py`.

- [ ] Record the exact base and establish a clean branch in Cursor's assigned worktree:

  ```bash
  git rev-parse --show-toplevel
  git status --short
  git fetch origin main
  git rev-parse origin/main
  git switch -c feat/b2-task-5-1b-replica-token-provider origin/main
  ```

  Expected top level:
  `D:/Projects/Development/Java/Spring/wealthmgmtandportfoliotracker-cursor`.

- [ ] Stop for architecture review if current `main` already contains any
  `ReplicaTokenProvider`, `ReplicaTokenFormula`, `ReplicaTokenTool`, `replicaTokenJar`, or third
  Azure-image smoke invocation, or if another open PR owns those surfaces.

## 1. Shared formula and resolve-once provider

**Files:**

- Create: `api-gateway/src/main/java/com/wealth/gateway/ReplicaTokenFormula.java`
- Create: `api-gateway/src/main/java/com/wealth/gateway/ReplicaTokenProvider.java`
- Test: `api-gateway/src/test/java/com/wealth/gateway/ReplicaTokenFormulaTest.java`
- Test: `api-gateway/src/test/java/com/wealth/gateway/ReplicaTokenProviderTest.java`

**Interfaces:**

- `static String ReplicaTokenFormula.compute(String rawName)` returns `""` for null/blank and an
  exact lowercase 12-hex token otherwise.
- `ReplicaTokenProvider()` reads `CONTAINER_APP_REPLICA_NAME` once.
- Package-visible `ReplicaTokenProvider(String rawName)` is the deterministic test seam.
- Package-visible `String replicaToken()` returns only the normalized token.

- [ ] **Step 1: Write failing formula tests.** Cover null, empty, ASCII whitespace, Unicode
  whitespace (`"\u2003"`), the fixed vector, determinism, lowercase 12-hex shape, and preservation
  of non-blank leading/trailing whitespace (no implicit `trim()`). The fixed-vector assertion is:

  ```java
  assertThat(ReplicaTokenFormula.compute("api-gateway--0000000-abcdefg"))
          .isEqualTo("95ca17821ade")
          .matches("[0-9a-f]{12}");
  ```

- [ ] **Step 2: Run the focused formula test and confirm RED because the class is absent.**

  ```bash
  ./gradlew :api-gateway:test --tests '*ReplicaTokenFormulaTest' --no-daemon
  ```

- [ ] **Step 3: Implement the minimal formula.** Use JDK APIs only:

  ```java
  final class ReplicaTokenFormula {
      private ReplicaTokenFormula() {
      }

      static String compute(String rawName) {
          if (rawName == null || rawName.isBlank()) {
              return "";
          }
          try {
              byte[] digest = MessageDigest.getInstance("SHA-256")
                      .digest(rawName.getBytes(StandardCharsets.UTF_8));
              return HexFormat.of().formatHex(digest, 0, 6);
          } catch (NoSuchAlgorithmException impossible) {
              throw new IllegalStateException("SHA-256 is unavailable", impossible);
          }
      }
  }
  ```

  Do not duplicate this algorithm in the provider, tool, tests, shell, or workflow.

- [ ] **Step 4: Run the focused formula test and confirm GREEN.**

- [ ] **Step 5: Write failing provider tests.** Assert null/empty/ASCII-whitespace/Unicode-whitespace
  all expose `""`; the fixed raw value exposes `95ca17821ade`; repeated accessor calls are stable;
  and no raw value is returned.

- [ ] **Step 6: Run the provider test and confirm RED because the provider is absent.**

  ```bash
  ./gradlew :api-gateway:test --tests '*ReplicaTokenProviderTest' --no-daemon
  ```

- [ ] **Step 7: Implement the provider minimally.** Mirror `InternalApiKeyProvider`'s constructor
  shape and delegate immediately to the formula:

  ```java
  @Component
  public final class ReplicaTokenProvider {
      private final String replicaToken;

      public ReplicaTokenProvider() {
          this(System.getenv("CONTAINER_APP_REPLICA_NAME"));
      }

      ReplicaTokenProvider(String rawName) {
          this.replicaToken = ReplicaTokenFormula.compute(rawName);
      }

      String replicaToken() {
          return replicaToken;
      }
  }
  ```

  `System.getenv("CONTAINER_APP_REPLICA_NAME")` must appear in production source only in this
  provider. `ReplicaTokenTool` receives its raw value as an argument instead.

- [ ] **Step 8: Run both focused test classes and confirm GREEN.**

## 2. Operator tool and deterministic jar

**Files:**

- Create: `api-gateway/src/main/java/com/wealth/gateway/ReplicaTokenTool.java`
- Create: `api-gateway/src/test/java/com/wealth/gateway/ReplicaTokenToolTest.java`
- Modify: `api-gateway/build.gradle`

**Interfaces:**

- `ReplicaTokenTool` accepts exactly one non-blank raw replica name.
- Valid invocation delegates to `ReplicaTokenFormula.compute`, prints exactly `<token>\n`, emits
  nothing to stderr, and exits `0`.
- Missing, blank, or extra arguments exit nonzero. Do not assert an exact diagnostic message; the
  success path is the externally governed byte contract.
- Gradle task `replicaTokenJar` produces `api-gateway/build/libs/replica-token.jar` with
  `Main-Class: com.wealth.gateway.ReplicaTokenTool`.

- [ ] **Step 1: Write failing child-process tests for the packaged tool.** Model the existing
  presence-probe test: locate `build/libs/replica-token.jar`, launch the current JDK with `-jar`,
  capture stdout and stderr independently, and assert the fixed success vector byte-for-byte.
  Also assert missing, blank, and extra arguments return nonzero.

- [ ] **Step 2: Add `replicaTokenJar` before running the test, so the failure is about missing tool
  classes or behavior rather than a nonexistent artifact.** Use this exact Gradle responsibility:

  ```groovy
  tasks.register('replicaTokenJar', Jar) {
      dependsOn tasks.named('classes')
      archiveFileName = 'replica-token.jar'
      from(sourceSets.main.output) {
          include 'com/wealth/gateway/ReplicaTokenTool.class'
          include 'com/wealth/gateway/ReplicaTokenFormula.class'
      }
      manifest {
          attributes 'Main-Class': 'com.wealth.gateway.ReplicaTokenTool'
      }
  }

  tasks.named('test') {
      dependsOn tasks.named('probeJar'), tasks.named('replicaTokenJar')
      finalizedBy tasks.named('jacocoTestReport')
  }
  ```

  Replace the current single `probeJar` dependency; do not create a second `test` block.

- [ ] **Step 3: Run the focused tool test and confirm RED because the executable behavior is
  absent.**

  ```bash
  ./gradlew :api-gateway:test --tests '*ReplicaTokenToolTest' --no-daemon
  ```

- [ ] **Step 4: Implement the minimal tool.** Keep successful output exact and delegate hashing:

  ```java
  public final class ReplicaTokenTool {
      private ReplicaTokenTool() {
      }

      public static void main(String[] args) {
          if (args.length != 1 || args[0].isBlank()) {
              System.err.print("expected exactly one non-blank replica name\n");
              System.exit(2);
              return;
          }
          System.out.print(ReplicaTokenFormula.compute(args[0]) + "\n");
      }
  }
  ```

- [ ] **Step 5: Run the focused tool test and confirm GREEN.**

- [ ] **Step 6: Build and inspect all three deterministic artifacts.**

  ```bash
  ./gradlew :api-gateway:bootJar :api-gateway:probeJar :api-gateway:replicaTokenJar --no-daemon
  jar tf api-gateway/build/libs/replica-token.jar
  unzip -p api-gateway/build/libs/replica-token.jar META-INF/MANIFEST.MF
  java -jar api-gateway/build/libs/replica-token.jar api-gateway--0000000-abcdefg
  ```

  Require exactly `app.jar`, `probe.jar`, and `replica-token.jar` for the governed artifacts; the
  tool jar contains `ReplicaTokenTool.class` and `ReplicaTokenFormula.class`, its manifest names
  the tool, and the invocation emits exactly `95ca17821ade\n` with empty stderr.

## 3. Azure image packaging

**File:** Modify `api-gateway/Dockerfile.azure`.

- [ ] **Step 1: Update the builder command to invoke all three tasks.**

  ```dockerfile
  RUN chmod +x gradlew \
      && ./gradlew :api-gateway:bootJar :api-gateway:probeJar \
          :api-gateway:replicaTokenJar --no-daemon
  ```

- [ ] **Step 2: Add the third exact runtime copy.** Preserve the existing two exact copies and the
  application entrypoint:

  ```dockerfile
  COPY --from=builder /workspace/api-gateway/build/libs/app.jar /app.jar
  COPY --from=builder /workspace/api-gateway/build/libs/probe.jar /probe.jar
  COPY --from=builder /workspace/api-gateway/build/libs/replica-token.jar /replica-token.jar
  ```

  Do not touch `api-gateway/Dockerfile` (AWS), use a wildcard, change the runtime image, or change
  `ENTRYPOINT ["java", "-jar", "/app.jar"]`.

- [ ] **Step 3: Build the actual Azure image and exercise the tool directly.**

  ```bash
  docker build -f api-gateway/Dockerfile.azure -t replica-token-smoke-test .
  docker run --rm --entrypoint java replica-token-smoke-test \
    -jar /replica-token.jar api-gateway--0000000-abcdefg
  ```

  Require exact stdout `95ca17821ade\n`, byte-empty stderr, and exit `0`.

## 4. Extend the existing Azure image smoke job

**Files:**

- Modify: `.github/workflows/ci-verification.yml`
- Modify: `scripts/tests/test_classify_changed_paths.py`

**Frozen topology:** Keep the job named `azure-image-smoke-test`, keep `needs: unit-tests`, keep it
without a job-level `if:`, and keep the existing eight entries in `ci-required.needs`. This task
adds a third case inside the existing job; it does not add a ninth dependency or alter branch
protection.

- [ ] **Step 1: Add failing workflow-contract assertions.** Require the existing job body to name
  `/replica-token.jar`, the raw fixed vector, the exact expected token, a byte comparison using
  `cmp`, a byte-empty stderr check, and a nonzero-exit failure path. Preserve all existing probe
  output-oracle regression tests.

- [ ] **Step 2: Run the workflow-contract suite and confirm RED because the third case is absent.**

  ```bash
  python scripts/tests/test_classify_changed_paths.py -v
  ```

- [ ] **Step 3: Extend the existing workflow job after its two presence-probe cases.** Capture the
  tool invocation's stdout and stderr into separate temporary files, fail explicitly on nonzero,
  compare stdout byte-for-byte with `printf '%s' $'95ca17821ade\n'`, and require an empty stderr
  file. Invoke:

  ```bash
  docker run --rm --entrypoint java probe-smoke-test \
    -jar /replica-token.jar api-gateway--0000000-abcdefg
  ```

  Keep the existing image tag and existing `blank`/`nonblank` cases unchanged. Do not use command
  substitution for the newline-bearing oracle; the current `cmp` contract exists because
  `$(...)` strips trailing newlines.

- [ ] **Step 4: Rerun the workflow-contract suite and confirm GREEN.**

## 5. Governed status documentation

**Files:**

- Modify: `.kiro/specs/asset-picker-composition/tasks.md`
- Modify: `docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md`

- [ ] After implementation and all local verification are complete, update both authorities to
  say Task 5.1b is **implemented but unmerged** on the feature branch. Keep its checkbox unchecked.
  Do not claim deployment, live proof, Task 5.1/8.7 consumer completion, or production exposure.

- [ ] Preserve Task 5.1a's merged status and all B1 G5 wording. Do not make an owner-controlled G5
  decision inside this PR.

## 6. Complete local verification

- [ ] Run the focused tests:

  ```bash
  ./gradlew :api-gateway:test \
    --tests '*ReplicaTokenFormulaTest' \
    --tests '*ReplicaTokenProviderTest' \
    --tests '*ReplicaTokenToolTest' --no-daemon
  ```

- [ ] Run the complete api-gateway suites and artifact build:

  ```bash
  ./gradlew :api-gateway:test :api-gateway:integrationTest --no-daemon
  ./gradlew :api-gateway:bootJar :api-gateway:probeJar \
    :api-gateway:replicaTokenJar --no-daemon
  ```

- [ ] Build and run the real Azure image third case, then rerun both pre-existing presence-probe
  cases to prove the extension did not regress them:

  ```bash
  docker build -f api-gateway/Dockerfile.azure -t replica-token-smoke-test .
  docker run --rm --entrypoint java -e INTERNAL_API_KEY= replica-token-smoke-test -jar /probe.jar
  docker run --rm --entrypoint java -e INTERNAL_API_KEY=smoke-test-value \
    replica-token-smoke-test -jar /probe.jar
  docker run --rm --entrypoint java replica-token-smoke-test \
    -jar /replica-token.jar api-gateway--0000000-abcdefg
  ```

- [ ] Run governance and workflow tests:

  ```bash
  python scripts/tests/test_classify_changed_paths.py -v
  python scripts/tests/test_master_plan_status_propagation.py -v
  ```

- [ ] Run the repository-pinned Actionlint invocation used by CI. Do not download or substitute an
  unpinned binary.

- [ ] Run final scope and hygiene checks:

  ```bash
  git diff --check
  git status --short
  git diff --name-only origin/main...HEAD
  git grep -n 'System.getenv("CONTAINER_APP_REPLICA_NAME")' -- api-gateway/src/main
  ```

  Require exactly one production environment reader (`ReplicaTokenProvider`), no raw replica value
  in any output contract, no AWS Dockerfile change, no consumer implementation, and no premature
  checkbox.

## 7. Commit and PR contract

- [ ] Prefer small reviewable commits: formula/provider and tests; tool/jar/image packaging; CI
  contract; governed status documentation. If keeping RED/GREEN evidence requires fewer commits,
  explain the sequence in the PR instead of manufacturing history.

- [ ] Push the source branch and open a non-draft PR against `main`. The body must contain exactly
  one declaration:

  ```text
  Master-plan impact: updated — B2, process
  ```

- [ ] Stop with the PR open. Do not enable auto-merge and do not merge it.

## 8. Live CI acceptance and Cursor completion report

- [ ] Treat the PR-event workflow run as authoritative. Require `docs_only=false`, all eight
  aggregate dependencies `success`, `azure-image-smoke-test=success`, and
  `ci-required=success`. Record whether the third case materially extends the critical path.

- [ ] Report:

  1. exact base, branch, and commit SHA;
  2. exact files changed;
  3. RED/GREEN evidence for each new component;
  4. focused and complete api-gateway test counts;
  5. exact jar filenames and manifest result;
  6. all three local Azure-image outputs and stderr/exit results;
  7. workflow-contract, master-plan guard, and Actionlint results;
  8. authoritative PR-event CI run and aggregate evidence;
  9. confirmation that Task 5.1, Task 8.7, deployment, production, AWS Dockerfile, and feature flags
     were untouched; and
  10. PR URL, still unmerged and awaiting senior review.
