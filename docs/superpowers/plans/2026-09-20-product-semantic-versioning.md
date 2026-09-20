# Product Semantic Versioning Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **OWNER APPROVAL CALLOUT — implementation and publication are not authorized by this plan.**
> Drafting this plan does not authorize repository edits beyond the planning artifacts, a commit,
> push, pull request, merge, tag, GitHub Release, ruleset change, Phase 3 execution, deployment, or
> Production contact. Stop at each named owner gate.

**Goal:** Establish one governed product SemVer at `0.9.0`, make version drift fail CI, safely rehearse a non-deploying pre-release, and leave the repository ready to enter Phase 3.

**Architecture:** A root `VERSION` file is the product-version authority. Gradle consumes it, the private frontend package mirrors it, a stdlib-only validator enforces bytes/SemVer/changelog/build-context/package/tag contracts, and the existing required `static-guard` runs that validator. Product SemVer labels a release set; Git SHA, frontend deployment identity, container digest, and serving revision remain the exact artifact identity.

**Tech Stack:** Git, Semantic Versioning 2.0 policy subset, Gradle/Groovy, Python 3.12 stdlib `unittest`, npm/package-lock v3, Docker/BuildKit, GitHub Actions, Markdown.

**Spec:** `docs/superpowers/specs/2026-09-20-product-semantic-versioning-design.md`

## Global Constraints

- The initial product version is exactly `0.9.0`; `1.0.0` remains reserved for the accepted demo-ready release set.
- `VERSION` is ASCII `0.9.0` followed by exactly one LF, with no BOM, CR, extra line, or build metadata.
- Product release tags are exactly `v<VERSION>`; release candidates use values such as `1.0.0-rc.1`.
- `v1.0-modular-monolith` remains untouched and is never accepted as a product release tag.
- Product SemVer is one monorepo release-set version; services do not receive independent SemVer lines.
- Git SHA, frontend deployment identity, OCI digest, and serving revision remain authoritative artifact identity.
- Do not modify or reinterpret `SERVICE_VERSION`, Terraform, deployed environment variables, registry tags, or deployment behavior.
- `infrastructure/package.json` and `scripts/package.json` retain independent tool versions and are excluded by allowlist.
- No tag or GitHub Release may trigger deployment.
- The demonstration is desktop-only; the known 320px/375px overflows remain backlog items and do not block Phase 3 or `1.0.0`.
- Release PR, merge, tag, GitHub Release, Phase 3, deployment, and Production acceptance are separate owner decisions.

## Review Focus

- Windows writes `VERSION` with CRLF or a UTF-8 BOM: validation must fail with the exact byte defect rather than silently trimming it (Task 1 tests).
- A valid-looking but noncanonical version such as `01.0.0`, `1.0`, or `1.0.0+sha` appears: validation must fail before any build or tag action (Task 1 tests).
- A service Dockerfile evaluates root `build.gradle` without copying `VERSION`: validation must fail before Docker reaches Gradle (Task 2 tests).
- Frontend `package.json` and either lockfile version field drift independently: validation must fail and name the mismatched field (Task 3 tests).
- A historical/nonmatching `v*` tag or an `Unreleased` changelog is used for publication: tag validation must fail, while branch/PR validation remains green (Tasks 1 and 4 tests).

---

### Task 1: Establish the product-version contract and validator core

**Files:**
- Create: `VERSION`
- Create: `CHANGELOG.md`
- Create: `docs/release/SEMANTIC_VERSIONING_POLICY.md`
- Create: `scripts/validate_product_version.py`
- Create: `scripts/tests/test_validate_product_version.py`
- Modify: `.gitattributes`

**Interfaces:**
- Produces: `read_product_version(root: Path) -> str`
- Produces: `validate_changelog(root: Path, version: str, require_released: bool) -> None`
- Produces: `validate_release_ref(version: str, ref_type: str, ref_name: str) -> None`
- Produces: `validate_repository(root: Path, ref_type: str = "", ref_name: str = "") -> str`
- Produces: CLI `python scripts/validate_product_version.py [--root PATH] [--ref-type TYPE] [--ref-name NAME]`

- [ ] **Step 1: Write core contract tests before creating `VERSION`**

Create `scripts/tests/test_validate_product_version.py` using stdlib `unittest`. Load the script by
path, create isolated temporary repositories, and pin these cases:

```python
#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "scripts" / "validate_product_version.py"


def load_validator():
    spec = importlib.util.spec_from_file_location("validate_product_version", SCRIPT)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {SCRIPT}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class ProductVersionCoreTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.validator = load_validator()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def write(self, relative: str, content: bytes) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)

    def valid_contract(self, version: str = "0.9.0", released: bool = False) -> None:
        self.write("VERSION", f"{version}\n".encode("ascii"))
        state = "2026-09-20" if released else "Unreleased"
        self.write("CHANGELOG.md", f"# Changelog\n\n## [{version}] - {state}\n".encode())

    def test_accepts_canonical_release(self):
        self.valid_contract()
        self.assertEqual("0.9.0", self.validator.validate_repository(self.root))

    def test_accepts_release_candidate(self):
        self.valid_contract("1.0.0-rc.1")
        self.assertEqual("1.0.0-rc.1", self.validator.validate_repository(self.root))

    def test_rejects_crlf_bom_missing_lf_and_extra_line(self):
        bad_values = (b"0.9.0\r\n", b"\xef\xbb\xbf0.9.0\n", b"0.9.0", b"0.9.0\nextra\n")
        for value in bad_values:
            with self.subTest(value=value):
                self.write("VERSION", value)
                self.write("CHANGELOG.md", b"# Changelog\n\n## [0.9.0] - Unreleased\n")
                with self.assertRaises(self.validator.ContractError):
                    self.validator.validate_repository(self.root)

    def test_rejects_noncanonical_versions_and_build_metadata(self):
        for version in ("01.0.0", "1.00.0", "1.0", "1.0.0+sha", "1.0.0-01"):
            with self.subTest(version=version):
                self.valid_contract(version)
                with self.assertRaises(self.validator.ContractError):
                    self.validator.validate_repository(self.root)

    def test_tag_requires_exact_version_and_released_changelog(self):
        self.valid_contract(released=True)
        self.assertEqual(
            "0.9.0",
            self.validator.validate_repository(self.root, "tag", "v0.9.0"),
        )
        for tag in ("v0.9.1", "0.9.0", "v1.0-modular-monolith"):
            with self.subTest(tag=tag), self.assertRaises(self.validator.ContractError):
                self.validator.validate_repository(self.root, "tag", tag)

    def test_tag_rejects_unreleased_changelog(self):
        self.valid_contract(released=False)
        with self.assertRaises(self.validator.ContractError):
            self.validator.validate_repository(self.root, "tag", "v0.9.0")


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run the tests and confirm the missing validator fails**

Run:

```powershell
python scripts/tests/test_validate_product_version.py -v
```

Expected: FAIL while loading `scripts/validate_product_version.py` because it does not exist.

- [ ] **Step 3: Create the exact version and changelog files**

Create `VERSION` with these exact bytes:

```text
0.9.0
```

Create `CHANGELOG.md`:

```markdown
# Changelog

All notable product-release changes are recorded here. Historical implementation records remain
under `docs/changes/`.

## [Unreleased]

## [0.9.0] - Unreleased

### Added

- Established the governed product Semantic Versioning foundation.

### Status

- Pre-demo-certification release line. Phase 3 Production E2E and final `1.0.0` acceptance remain
  pending.
```

Add this line to `.gitattributes`:

```gitattributes
/VERSION text eol=lf
```

- [ ] **Step 4: Implement the validator core**

Create `scripts/validate_product_version.py` with these contracts. Keep it stdlib-only:

```python
#!/usr/bin/env python3
from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SEMVER = re.compile(
    r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"
    r"(?:-((?:0|[1-9]\d*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)"
    r"(?:\.(?:0|[1-9]\d*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*))?$"
)


class ContractError(ValueError):
    pass


def read_product_version(root: Path) -> str:
    path = root / "VERSION"
    try:
        data = path.read_bytes()
    except OSError as exc:
        raise ContractError(f"{path}: cannot read VERSION: {exc}") from exc
    if data.startswith(b"\xef\xbb\xbf"):
        raise ContractError(f"{path}: UTF-8 BOM is forbidden")
    if b"\r" in data:
        raise ContractError(f"{path}: CR is forbidden; use LF")
    if not data.endswith(b"\n") or data.count(b"\n") != 1:
        raise ContractError(f"{path}: require exactly one line ending in one LF")
    try:
        version = data[:-1].decode("ascii")
    except UnicodeDecodeError as exc:
        raise ContractError(f"{path}: VERSION must be ASCII") from exc
    if not SEMVER.fullmatch(version):
        raise ContractError(
            f"{path}: {version!r} is not canonical SemVer without build metadata"
        )
    return version


def validate_changelog(root: Path, version: str, require_released: bool) -> None:
    path = root / "CHANGELOG.md"
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise ContractError(f"{path}: cannot read changelog: {exc}") from exc
    match = re.search(
        rf"(?m)^## \[{re.escape(version)}\] - (Unreleased|\d{{4}}-\d{{2}}-\d{{2}})$",
        text,
    )
    if match is None:
        raise ContractError(f"{path}: missing release heading for {version}")
    if require_released and match.group(1) == "Unreleased":
        raise ContractError(f"{path}: {version} must be dated before tagging")


def validate_release_ref(version: str, ref_type: str, ref_name: str) -> None:
    if ref_type == "tag" and ref_name != f"v{version}":
        raise ContractError(f"release tag must be exactly v{version}, got {ref_name!r}")


def validate_repository(
    root: Path, ref_type: str = "", ref_name: str = ""
) -> str:
    version = read_product_version(root)
    validate_release_ref(version, ref_type, ref_name)
    validate_changelog(root, version, require_released=ref_type == "tag")
    return version


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=REPO)
    parser.add_argument("--ref-type", default=os.environ.get("GITHUB_REF_TYPE", ""))
    parser.add_argument("--ref-name", default=os.environ.get("GITHUB_REF_NAME", ""))
    args = parser.parse_args(argv)
    try:
        version = validate_repository(args.root.resolve(), args.ref_type, args.ref_name)
    except ContractError as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1
    print(f"PASS: product version {version}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 5: Write the repository policy from the approved design**

Create `docs/release/SEMANTIC_VERSIONING_POLICY.md`. Copy the normative rules from design §§5–13:
the version domains, pre/post-1.0 bump table, `1.0.0` gate, source/digest distinction, changelog
contract, historical-tag disposition, owner gates, and the rule that tags never deploy. Do not copy
the design alternatives or brainstorming history into the operational policy.

- [ ] **Step 6: Run the core contract and byte checks**

Run:

```powershell
python scripts/tests/test_validate_product_version.py -v
python scripts/validate_product_version.py
$bytes = [IO.File]::ReadAllBytes((Resolve-Path VERSION))
[Convert]::ToHexString($bytes)
git diff --check
```

Expected: all tests PASS; validator prints `PASS: product version 0.9.0`; hex is
`302E392E300A`; `git diff --check` is silent.

- [ ] **Step 7: Commit Task 1 after its independent review**

```powershell
git add VERSION CHANGELOG.md .gitattributes docs/release/SEMANTIC_VERSIONING_POLICY.md scripts/validate_product_version.py scripts/tests/test_validate_product_version.py
git commit -m "feat(release): establish product version contract"
```

### Task 2: Make Gradle and service Docker builds consume `VERSION`

**Files:**
- Modify: `build.gradle:12`
- Modify: `api-gateway/Dockerfile`
- Modify: `api-gateway/Dockerfile.azure`
- Modify: `portfolio-service/Dockerfile`
- Modify: `portfolio-service/Dockerfile.azure`
- Modify: `market-data-service/Dockerfile`
- Modify: `market-data-service/Dockerfile.azure`
- Modify: `insight-service/Dockerfile`
- Modify: `insight-service/Dockerfile.azure`
- Modify: `scripts/validate_product_version.py`
- Modify: `scripts/tests/test_validate_product_version.py`

**Interfaces:**
- Consumes: canonical version from `read_product_version()` and root `VERSION`
- Produces: Gradle root and all subprojects at `0.9.0`
- Produces: `validate_gradle_docker_contexts(root: Path) -> None`

- [ ] **Step 1: Add a failing Docker-context contract test**

Add a test that calls `validate_gradle_docker_contexts(REPO)`. Before the Dockerfiles change it must
fail and identify all eight Gradle-building Dockerfiles. Add a temporary-root test in which
`COPY VERSION VERSION` occurs after `RUN ./gradlew`; it must also fail because the version is not
available when Gradle configures the build.

- [ ] **Step 2: Run the focused test and confirm the expected failure**

```powershell
python scripts/tests/test_validate_product_version.py -v
```

Expected: FAIL naming the Dockerfiles that copy `build.gradle` but not `VERSION` before Gradle.

- [ ] **Step 3: Replace the hard-coded Gradle version with the root contract**

Replace `version = '0.0.1-SNAPSHOT'` in root `build.gradle` with:

```groovy
def productVersionFile = layout.projectDirectory.file('VERSION').asFile
if (!productVersionFile.isFile()) {
    throw new GradleException("Missing product VERSION at ${productVersionFile}")
}
def productVersion = productVersionFile.getText('UTF-8').trim()

group = 'com.wealth'
version = productVersion
```

At the beginning of the existing `subprojects` block, add:

```groovy
    group = rootProject.group
    version = rootProject.version
```

Do not add `-SNAPSHOT` and do not change dependency versions.

- [ ] **Step 4: Put `VERSION` into every Gradle-building Docker context**

In each of the eight AWS/Azure service Dockerfiles, add this line immediately after
`COPY build.gradle build.gradle`:

```dockerfile
COPY VERSION VERSION
```

Do not change `Dockerfile.candidate` or `Dockerfile.slim-it`; those package already staged JARs and
do not evaluate root Gradle configuration.

- [ ] **Step 5: Extend the validator with the exact Docker allowlist**

Add the eight relative paths as a tuple. For each file, require `COPY VERSION VERSION` to occur
after the `COPY build.gradle build.gradle` line and before the first `RUN` block containing
`./gradlew`. Call the function from `validate_repository()`. Do not scan arbitrary Dockerfiles;
the allowlist documents which images consume the root build.

- [ ] **Step 6: Run contract, Gradle-resolution, and packaging checks**

```powershell
python scripts/tests/test_validate_product_version.py -v
python scripts/validate_product_version.py
./gradlew.bat -q properties | Select-String '^version: 0.9.0$'
./gradlew.bat -q :api-gateway:properties | Select-String '^version: 0.9.0$'
./gradlew.bat -q :portfolio-service:properties | Select-String '^version: 0.9.0$'
./gradlew.bat :api-gateway:bootJar --no-daemon
git diff --check
```

Expected: validator/tests PASS; all three property checks return `version: 0.9.0`; `bootJar` succeeds
and still produces `api-gateway/build/libs/app.jar`; whitespace check is silent.

- [ ] **Step 7: Commit Task 2 after its independent review**

```powershell
git add build.gradle api-gateway/Dockerfile api-gateway/Dockerfile.azure portfolio-service/Dockerfile portfolio-service/Dockerfile.azure market-data-service/Dockerfile market-data-service/Dockerfile.azure insight-service/Dockerfile insight-service/Dockerfile.azure scripts/validate_product_version.py scripts/tests/test_validate_product_version.py
git commit -m "build: source service versions from VERSION"
```

### Task 3: Align the private frontend package with the product version

**Files:**
- Modify: `frontend/package.json:3`
- Modify: `frontend/package-lock.json`
- Modify: `scripts/validate_product_version.py`
- Modify: `scripts/tests/test_validate_product_version.py`

**Interfaces:**
- Consumes: root product version returned by `read_product_version()`
- Produces: `validate_frontend_versions(root: Path, version: str) -> None`
- Produces: frontend package and lockfile root package at `0.9.0`

- [ ] **Step 1: Add failing package and lockfile drift tests**

Add temporary-repository cases for:

```python
package.json version != VERSION
package-lock.json top-level version != VERSION
package-lock.json packages[""] version != VERSION
malformed JSON or missing packages[""]
```

Each failure must name the exact file/field. Add a repository test that calls
`validate_frontend_versions(REPO, "0.9.0")`; it initially fails on the current `0.1.0` values.

- [ ] **Step 2: Run the test and confirm current frontend drift**

```powershell
python scripts/tests/test_validate_product_version.py -v
```

Expected: FAIL showing `frontend/package.json` is `0.1.0`, expected `0.9.0`.

- [ ] **Step 3: Implement exact frontend JSON validation**

Use `json.loads()` and require all three fields to equal `version`:

```python
package["version"]
lockfile["version"]
lockfile["packages"][""]["version"]
```

Call `validate_frontend_versions(root, version)` from `validate_repository()`. Do not inspect
`infrastructure/package.json` or `scripts/package.json`.

- [ ] **Step 4: Update package and lock metadata through npm**

```powershell
Set-Location frontend
npm version 0.9.0 --no-git-tag-version --allow-same-version
Set-Location ..
```

Inspect the diff and confirm only `frontend/package.json` plus the two root-package version fields
in `frontend/package-lock.json` changed. Do not update dependencies.

- [ ] **Step 5: Run frontend and product-version checks**

```powershell
python scripts/tests/test_validate_product_version.py -v
python scripts/validate_product_version.py
Set-Location frontend
npm ci
npm test
npm run build
Set-Location ..
git diff --check
```

Expected: validator/tests PASS, npm install succeeds without lockfile modification, frontend tests
and static-export build pass, and whitespace check is silent.

- [ ] **Step 6: Commit Task 3 after its independent review**

```powershell
git add frontend/package.json frontend/package-lock.json scripts/validate_product_version.py scripts/tests/test_validate_product_version.py
git commit -m "build(frontend): align product version metadata"
```

### Task 4: Make release-tag validation required without enabling deployment

**Files:**
- Create: `scripts/tests/test_product_version_ci_wiring.py`
- Modify: `.github/workflows/ci-verification.yml:3-7`
- Modify: `.github/workflows/ci-verification.yml:59-116`

**Interfaces:**
- Consumes: validator CLI and GitHub-provided `GITHUB_REF_TYPE`/`GITHUB_REF_NAME`
- Produces: required validation on PRs, configured branch pushes, and `v*` tag pushes
- Preserves: deploy workflows have no `push.tags` or `release` trigger

- [ ] **Step 1: Write a failing CI-wiring contract test**

Create a stdlib `unittest` file that:

1. extracts the top-level `on:` block from YAML text without PyYAML;
2. requires `ci-verification.yml` to contain `tags: ["v*"]` under `on.push`;
3. requires a `Product version contract` step in `static-guard` running both the validator tests and
   validator CLI; and
4. extracts the top-level `on:` block from `deploy.yml`, `deploy-azure.yml`,
   `deploy-azure-frontend.yml`, and `deploy-aws.yml`, rejecting `tags:` and `release:` there.

The extractor must stop at the next unindented YAML key so `frontend-cd.yml`-style action output
`tags:` cannot be confused with an event trigger.

- [ ] **Step 2: Run the wiring test and confirm the missing tag route fails**

```powershell
python scripts/tests/test_product_version_ci_wiring.py -v
```

Expected: FAIL because CI does not yet listen to `v*` tags and has no product-version step.

- [ ] **Step 3: Add validation-only tag routing**

Change the CI trigger to:

```yaml
on:
  push:
    branches: ["main", "architecture/**", "feature/**"]
    tags: ["v*"]
  pull_request:
    branches: ["main", "architecture/**"]
```

Do not edit any deploy workflow trigger.

- [ ] **Step 4: Add the required static-guard step**

Under `static-guard`, after Python setup and before unrelated guards, add:

```yaml
      - name: Product version contract
        run: |
          python scripts/tests/test_validate_product_version.py -v
          python scripts/tests/test_product_version_ci_wiring.py -v
          python scripts/validate_product_version.py
```

The CLI reads `GITHUB_REF_TYPE` and `GITHUB_REF_NAME` from the runner. On branches and pull
requests it validates source state; on tags it additionally requires exact tag equality and a dated
changelog.

- [ ] **Step 5: Run local workflow contracts**

```powershell
python scripts/tests/test_validate_product_version.py -v
python scripts/tests/test_product_version_ci_wiring.py -v
python scripts/validate_product_version.py
python scripts/tests/test_classify_changed_paths.py -v
python scripts/tests/test_deploy_frontend_only_mode.py -v
git diff --check
```

Expected: all tests and validator PASS; no deploy workflow acquires a tag/release trigger.

- [ ] **Step 6: Commit Task 4 after its independent review**

```powershell
git add .github/workflows/ci-verification.yml scripts/tests/test_product_version_ci_wiring.py
git commit -m "ci: enforce product version contract"
```

### Task 5: Reconcile the desktop-demo sequence and preserve responsive debt

**Files:**
- Modify: `docs/plans/ASSET_PICKER_DEMO_PREPARATION_PLAN.md:131-177`
- Create: `docs/todos/backlog/responsive-dashboard-narrow-width-overflow/README.md`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: approved desktop-only decision and SemVer source-complete evidence
- Produces: one current next-step sequence: SemVer rehearsal, then Phase 3 desktop E2E

- [ ] **Step 1: Record the two deferred responsive defects in one bounded backlog item**

Create the backlog entry with:

- status `Open — explicitly accepted for the desktop-only demo on 2026-09-20`;
- Portfolio holdings footer/action-area page overflow at 320px and 375px;
- Overview performance-range badge page overflow at 320px;
- current source locations `frontend/src/components/portfolio/HoldingsTable.tsx` and
  `frontend/src/components/charts/PerformanceChart.tsx`;
- rationale that desktop demonstration scope removes them from the demo-critical path but does not
  claim they are fixed;
- acceptance criteria preserving intentional component/table scrolling while eliminating page-level
  overflow; and
- a statement that any future mobile/tablet support decision reactivates them before release of that
  support.

- [ ] **Step 2: Reconcile Phase 2 and Phase 3 in the demo-preparation plan**

Update the Phase 2 status and checklist so the two overflow lines are checked as “explicitly
accepted and deferred for desktop-only demonstration,” linked to the backlog entry. Add a final
Phase 2 SemVer-foundation item linked to the design, plan, policy, and `0.9.0` validation evidence.

Update Phase 3 to say its browser matrix is desktop-only at the agreed viewport(s). Do not remove
the multi-user, isolation, persistence, conflict, freshness, evidence, or uncontended-run
requirements. State that `1.0.0` remains blocked until Phase 3 and finding remediation/acceptance
complete.

- [ ] **Step 3: Make the changelog accurately describe the source-complete slice**

Under `0.9.0`, retain `Unreleased` and add bullets for root version authority, Gradle/frontend
alignment, and required CI/tag validation. Do not date the entry or claim the tag/release exists.

- [ ] **Step 4: Validate documentation consistency**

```powershell
rg -n "0\.9\.0|1\.0\.0|desktop-only|responsive-dashboard-narrow-width-overflow" VERSION CHANGELOG.md docs/release/SEMANTIC_VERSIONING_POLICY.md docs/plans/ASSET_PICKER_DEMO_PREPARATION_PLAN.md docs/todos/backlog/responsive-dashboard-narrow-width-overflow/README.md
python scripts/validate_product_version.py
git diff --check
```

Expected: `0.9.0` is current and unreleased; `1.0.0` is future and gated; mobile overflows are open
backlog debt; Phase 3 is the next product phase after the release rehearsal.

- [ ] **Step 5: Commit Task 5 after its independent review**

```powershell
git add CHANGELOG.md docs/plans/ASSET_PICKER_DEMO_PREPARATION_PLAN.md docs/todos/backlog/responsive-dashboard-narrow-width-overflow/README.md
git commit -m "docs(plan): sequence SemVer foundation before Phase 3"
```

### Task 6: Run the uncontended source-completion gate

**Files:**
- Verify all files changed by Tasks 1–5

**Interfaces:**
- Produces: independently reviewed source-complete `0.9.0` foundation
- Does not produce: tag, GitHub Release, deployment, or Phase 3 evidence

- [ ] **Step 1: Confirm exact branch, base, and clean task boundary**

```powershell
git rev-parse --show-toplevel
git branch --show-current
git merge-base HEAD origin/main
git status --short --branch
git diff --check origin/main...HEAD
```

Expected: isolated SemVer branch/worktree; intended base; only planned files; no whitespace errors.

- [ ] **Step 2: Run the complete product-version contract**

```powershell
python scripts/tests/test_validate_product_version.py -v
python scripts/tests/test_product_version_ci_wiring.py -v
python scripts/validate_product_version.py
```

Expected: all tests PASS and validator reports `0.9.0`.

- [ ] **Step 3: Run build and existing regression checks**

```powershell
./gradlew.bat test --no-daemon
Set-Location frontend
npm ci
npm test
npm run build
Set-Location ..
python scripts/tests/test_classify_changed_paths.py -v
python scripts/tests/test_deploy_frontend_only_mode.py -v
```

Expected: all commands PASS. Report any unavailable Docker/host prerequisite as UNRUN, never PASS.

- [ ] **Step 4: Validate representative container configuration**

```powershell
docker build -f api-gateway/Dockerfile.azure -t wealth-semver-api-gateway:local .
docker image inspect wealth-semver-api-gateway:local --format '{{json .Config.Labels}}'
```

Expected: image builds successfully from the root context. This slice does not require new OCI
version labels, so the label inspection is evidence that no unplanned label claim was introduced.

- [ ] **Step 5: Obtain independent review**

Give the reviewer the spec, this plan, complete diff, validator/test output, Gradle/frontend output,
and the Docker result. Review focus is:

- no product/artifact identity conflation;
- no `SERVICE_VERSION`, Terraform, registry, or deployment expansion;
- exact SemVer/parser correctness;
- tag validation without tag-triggered deployment;
- Docker-context completeness; and
- truthful desktop-only plan reconciliation.

No model reviews its own work. Resolve every blocking finding and rerun affected checks.

- [ ] **Step 6: Commit review-driven corrections and stop at the publication gate**

Use one focused fix commit per accepted review round. Report exact HEAD, diff summary, validations,
review outcome, and any UNRUN check. Do not push or create a pull request without explicit owner
authorization.

### Task 7: Owner-gated `v0.9.0` pre-release rehearsal

**Files:**
- Modify in a release PR: `CHANGELOG.md`
- No application, workflow, infrastructure, or deployment files

**Interfaces:**
- Consumes: merged source-complete implementation and separate owner authorization
- Produces: annotated `v0.9.0`, GitHub pre-release, green tag validation, and proof that no deploy ran

> **OWNER GATE:** Do not begin this task without explicit authorization for the release PR, its
> push/merge, annotated tag publication, and GitHub pre-release. That authorization does not permit
> deployment or Production access.

- [ ] **Step 1: Prepare the release-only changelog PR**

Replace `## [0.9.0] - Unreleased` with the actual release date, run the full validator/tests, obtain
independent review, and request separate push/PR/merge authority. The PR must state:

```text
0.9.0 is a pre-demo-certification SemVer foundation release.
It is not 1.0.0, does not claim Phase 3 completion, and does not deploy anything.
```

- [ ] **Step 2: Re-observe the accepted release commit**

After merge, record the exact `main` SHA and run:

```powershell
python scripts/validate_product_version.py --ref-type tag --ref-name v0.9.0
git show --no-patch --format=fuller <accepted-main-sha>
git tag -l v0.9.0
```

Expected: validator PASS; the tag does not already exist. Stop on any mismatch.

- [ ] **Step 3: Create and publish the annotated tag only after owner approval**

```powershell
git tag -a v0.9.0 <accepted-main-sha> -m "Release 0.9.0 (pre-demo certification)"
git push origin v0.9.0
```

Do not move or recreate the tag after publication.

- [ ] **Step 4: Create the GitHub pre-release only after owner approval**

```powershell
gh release create v0.9.0 --verify-tag --prerelease --title "0.9.0 — SemVer foundation" --notes "Pre-demo-certification release. Establishes governed product Semantic Versioning; Phase 3 and 1.0.0 acceptance remain pending. No deployment is included."
```

- [ ] **Step 5: Prove validation ran and deployment did not**

Inspect Actions for the tag SHA. Require the product-version CI run to finish green. Confirm no run
of `deploy.yml`, `deploy-azure.yml`, `deploy-azure-frontend.yml`, or `deploy-aws.yml` was triggered by
the tag or Release. Record URLs and conclusions in the handoff; do not dispatch anything.

- [ ] **Step 6: Handoff to Phase 3**

Report `v0.9.0`, tag object/commit, GitHub Release URL, CI result, and no-deploy evidence. State that
Phase 3 still needs its own owner authorization and that `1.0.0` remains gated by the design’s §8
acceptance boundary.

## Self-review record

- **Spec coverage:** every design requirement maps to Tasks 1–7; runtime metadata expansion,
  `SERVICE_VERSION` remediation, registry aliases, automated bump inference, deployment, and Phase 3
  are explicitly excluded.
- **Placeholder scan:** the plan contains no unresolved implementation choices; `Unreleased` is a
  deliberate changelog state with an exact release transition in Task 7.
- **Type/interface consistency:** validator function names and CLI flags are defined once and consumed
  consistently by later tasks and CI.
- **Review Focus coverage:** exact-byte, SemVer, Docker-context, frontend-drift, historical-tag, and
  unreleased-tag cases each have an owning test task.
- **Scope check:** the implementation is one release-foundation subsystem. The owner-gated release
  rehearsal is separated from source implementation and from deployment.
