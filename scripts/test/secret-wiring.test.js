"use strict";

const { describe, it } = require("node:test");
const assert = require("node:assert/strict");
const {
  isCanonicalWorkflowPath,
  listWorkflowFiles,
  validateManifestSchema,
  validateNoDuplicateDiscovered,
  usesSanitizerAction,
  referencesSecretIndependently,
  isProvenConjunctiveGate,
  runGuard,
  resetFailures,
  getFailures,
  EXACT_SECRET_EXPRESSION,
} = require("../check-sanitizer-secret-wiring.js");

describe("isCanonicalWorkflowPath", () => {
  it("accepts .github/workflows/x.yml and .yaml", () => {
    assert.equal(isCanonicalWorkflowPath(".github/workflows/x.yml"), true);
    assert.equal(isCanonicalWorkflowPath(".github/workflows/x.yaml"), true);
  });

  it("rejects hostile workflow path shapes", () => {
    for (const s of [
      "../outside.yml",
      "/.github/workflows/x.yml",
      "C:/x.yml",
      ".github/workflows/a/../x.yml",
      ".github/workflows//x.yml",
      ".github/workflows/x\\y.yml",
    ]) {
      assert.equal(isCanonicalWorkflowPath(s), false, s);
    }
  });
});

describe("listWorkflowFiles", () => {
  it("produces exactly .github/workflows/x.yml from basename x.yml", () => {
    const files = listWorkflowFiles(() => ["x.yml"]);
    assert.deepEqual(files, [".github/workflows/x.yml"]);
  });

  it("rejects a mocked basename x\\y.yml", () => {
    resetFailures();
    const files = listWorkflowFiles(() => ["x\\y.yml"]);
    assert.deepEqual(files, []);
    assert.ok(getFailures().length > 0);
  });
});

describe("validateManifestSchema", () => {
  const base = {
    workflow: ".github/workflows/x.yml",
    job: "j",
    stepId: "s",
    playwright: true,
    baseline: "failure",
  };

  it("rejects a non-array top-level value", () => {
    resetFailures();
    validateManifestSchema({ nope: true });
    assert.ok(getFailures().some((m) => /top-level JSON array/.test(m)));
  });

  it("rejects playwright:true missing baseline or with a bad value", () => {
    resetFailures();
    validateManifestSchema([
      { workflow: base.workflow, job: "j", stepId: "s", playwright: true },
    ]);
    assert.ok(getFailures().length > 0);
    resetFailures();
    validateManifestSchema([{ ...base, baseline: "sometimes" }]);
    assert.ok(getFailures().length > 0);
  });

  it("rejects playwright:false carrying baseline", () => {
    resetFailures();
    validateManifestSchema([
      {
        workflow: base.workflow,
        job: "j",
        stepId: "s",
        playwright: false,
        baseline: "failure",
      },
    ]);
    assert.ok(getFailures().length > 0);
  });

  it("rejects missing/non-boolean playwright, extra fields, and duplicate keys", () => {
    resetFailures();
    validateManifestSchema([
      { workflow: base.workflow, job: "j", stepId: "s", playwright: "yes" },
    ]);
    assert.ok(getFailures().length > 0);
    resetFailures();
    validateManifestSchema([{ ...base, extra: 1 }]);
    assert.ok(getFailures().length > 0);
    resetFailures();
    validateManifestSchema([base, { ...base, baseline: "always" }]);
    assert.ok(getFailures().some((m) => /duplicate/.test(m)));
  });
});

describe("validateNoDuplicateDiscovered", () => {
  it("fails two discovered steps in one job with the same stepId", () => {
    resetFailures();
    validateNoDuplicateDiscovered([
      { workflow: "w", job: "j", stepId: "upload" },
      { workflow: "w", job: "j", stepId: "upload" },
    ]);
    assert.ok(getFailures().length > 0);
  });
});

describe("usesSanitizerAction", () => {
  it("matches the local action with or without a trailing slash", () => {
    assert.equal(
      usesSanitizerAction({
        uses: "./.github/actions/sanitize-playwright-artifacts",
      }),
      true,
    );
    assert.equal(
      usesSanitizerAction({
        uses: "./.github/actions/sanitize-playwright-artifacts/",
      }),
      true,
    );
    assert.equal(
      usesSanitizerAction({
        uses: "./.github/actions/sanitize-playwright-artifacts-extra",
      }),
      false,
    );
  });
});

describe("referencesSecretIndependently", () => {
  it("detects dot and index syntax, including job.container.credentials.password", () => {
    assert.equal(
      referencesSecretIndependently({
        container: { credentials: { password: "${{ secrets.E2E_TEST_USER_PASSWORD }}" } },
        steps: [],
      }),
      true,
    );
    assert.equal(
      referencesSecretIndependently({
        env: { P: "${{ secrets['E2E_TEST_USER_PASSWORD'] }}" },
        steps: [],
      }),
      true,
    );
    assert.equal(
      referencesSecretIndependently({
        env: { P: '${{ secrets["E2E_TEST_USER_PASSWORD"] }}' },
        steps: [],
      }),
      true,
    );
    assert.equal(
      referencesSecretIndependently({
        env: { P: "${{ secrets.e2e_test_user_password }}" },
        steps: [],
      }),
      true,
    );
  });

  it("detects a reference at job.services, job.outputs, or job-level if", () => {
    assert.equal(
      referencesSecretIndependently({
        services: { redis: { env: { P: "${{ secrets.E2E_TEST_USER_PASSWORD }}" } } },
        steps: [],
      }),
      true,
    );
    assert.equal(
      referencesSecretIndependently({
        outputs: { p: "${{ secrets.E2E_TEST_USER_PASSWORD }}" },
        steps: [],
      }),
      true,
    );
    assert.equal(
      referencesSecretIndependently({
        if: "${{ secrets.E2E_TEST_USER_PASSWORD != '' }}",
        steps: [],
      }),
      true,
    );
  });

  it("returns false when the only reference is inside a sanitizer step with", () => {
    assert.equal(
      referencesSecretIndependently({
        steps: [
          {
            uses: "./.github/actions/sanitize-playwright-artifacts",
            with: { "e2e-password": EXACT_SECRET_EXPRESSION },
          },
        ],
      }),
      false,
    );
  });
});

describe("isProvenConjunctiveGate", () => {
  it("accepts only the exact conjunction matching the classified baseline", () => {
    assert.equal(
      isProvenConjunctiveGate(
        "always() && steps.sanitize.outcome == 'success'",
        "sanitize",
        "always",
      ),
      true,
    );
    assert.equal(
      isProvenConjunctiveGate(
        "failure() && steps.sanitize.outcome == 'success'",
        "sanitize",
        "failure",
      ),
      true,
    );
    assert.equal(
      isProvenConjunctiveGate(
        "failure() && steps.sanitize.outcome == 'success'",
        "sanitize",
        "always",
      ),
      false,
    );
    assert.equal(
      isProvenConjunctiveGate(
        "always() && steps.sanitize.outcome == 'success'",
        "sanitize",
        "failure",
      ),
      false,
    );
  });

  it("rejects the three fail-open expressions", () => {
    for (const expr of [
      "always() || steps.sanitize.outcome == 'success'",
      "!(steps.sanitize.outcome == 'success')",
      "steps.sanitize.outcome == 'success' || failure()",
    ]) {
      assert.equal(isProvenConjunctiveGate(expr, "sanitize", "always"), false, expr);
      assert.equal(isProvenConjunctiveGate(expr, "sanitize", "failure"), false, expr);
    }
  });
});

describe("runGuard cardinality and inventory", () => {
  function yamlFor(jobs) {
    return `name: t\non: push\njobs:\n${jobs}`;
  }

  it("fails an independent secret reference with a missing, wrong-mode, or wrong-expression sanitizer", () => {
    const secretJob = (sanitizerBlock) =>
      yamlFor(`
  j:
    runs-on: ubuntu-latest
    env:
      E2E_TEST_USER_PASSWORD: \${{ secrets.E2E_TEST_USER_PASSWORD }}
    steps:
${sanitizerBlock}
      - id: up
        uses: actions/upload-artifact@v4
        with: { path: x }
`);
    const manifest = [
      {
        workflow: ".github/workflows/t.yml",
        job: "j",
        stepId: "up",
        playwright: false,
      },
    ];
    const run = (workflow) =>
      runGuard({
        readdirSync: () => ["t.yml"],
        readFileSync: (p) =>
          String(p).endsWith("playwright-upload-manifest.json")
            ? JSON.stringify(manifest)
            : workflow,
      });

    assert.ok(
      run(secretJob("      - run: echo no sanitizer")).some((m) =>
        /independently references the real secret/.test(m),
      ),
    );
    assert.ok(
      run(
        secretJob(`      - id: sanitize
        uses: ./.github/actions/sanitize-playwright-artifacts
        with:
          mode: fallback-only
          source-dir: a
          staging-dir: /tmp/a`),
      ).some((m) => /independently references the real secret/.test(m)),
    );
    assert.ok(
      run(
        secretJob(`      - id: sanitize
        uses: ./.github/actions/sanitize-playwright-artifacts
        with:
          mode: live-secret
          e2e-password: \${{ secrets.OTHER_PASSWORD }}
          source-dir: a
          staging-dir: /tmp/a`),
      ).some((m) => /independently references the real secret/.test(m)),
    );
  });

  it("fails a credential-free job with a malformed-mode sanitizer", () => {
    const workflow = yamlFor(`
  j:
    runs-on: ubuntu-latest
    steps:
      - id: sanitize
        uses: ./.github/actions/sanitize-playwright-artifacts
        with:
          mode: live-secret
          source-dir: a
          staging-dir: /tmp/a
      - id: up
        uses: actions/upload-artifact@v4
        with: { path: /tmp/a }
`);
    const failures = runGuard({
      readdirSync: () => ["t.yml"],
      readFileSync: (p) => {
        if (String(p).endsWith("playwright-upload-manifest.json")) {
          return JSON.stringify([
            {
              workflow: ".github/workflows/t.yml",
              job: "j",
              stepId: "up",
              playwright: false,
            },
          ]);
        }
        return workflow;
      },
    });
    assert.ok(failures.some((m) => /not exactly mode: fallback-only/.test(m)));
  });

  it("fails a credential-free job whose sanitizer has an explicitly-empty e2e-password", () => {
    const workflow = yamlFor(`
  j:
    runs-on: ubuntu-latest
    steps:
      - id: sanitize
        uses: ./.github/actions/sanitize-playwright-artifacts
        with:
          mode: fallback-only
          e2e-password: ""
          source-dir: a
          staging-dir: /tmp/a
      - id: up
        uses: actions/upload-artifact@v4
        with: { path: /tmp/a }
`);
    const failures = runGuard({
      readdirSync: () => ["t.yml"],
      readFileSync: (p) => {
        if (String(p).endsWith("playwright-upload-manifest.json")) {
          return JSON.stringify([
            {
              workflow: ".github/workflows/t.yml",
              job: "j",
              stepId: "up",
              playwright: false,
            },
          ]);
        }
        return workflow;
      },
    });
    assert.ok(failures.some((m) => /e2e-password entirely absent/.test(m)));
  });

  it("fails two sanitizer steps in one job", () => {
    const workflow = yamlFor(`
  j:
    runs-on: ubuntu-latest
    steps:
      - id: sanitize
        uses: ./.github/actions/sanitize-playwright-artifacts
        with: { mode: fallback-only, staging-dir: /tmp/a, source-dir: a }
      - id: sanitize2
        uses: ./.github/actions/sanitize-playwright-artifacts
        with: { mode: fallback-only, staging-dir: /tmp/b, source-dir: b }
      - id: up
        uses: actions/upload-artifact@v4
        with: { path: /tmp/a }
`);
    const failures = runGuard({
      readdirSync: () => ["t.yml"],
      readFileSync: (p) => {
        if (String(p).endsWith("playwright-upload-manifest.json")) {
          return JSON.stringify([
            {
              workflow: ".github/workflows/t.yml",
              job: "j",
              stepId: "up",
              playwright: false,
            },
          ]);
        }
        return workflow;
      },
    });
    assert.ok(failures.some((m) => /at most one per job/.test(m)));
  });

  it("fails a newly discovered step with no manifest entry", () => {
    const workflow = yamlFor(`
  j:
    runs-on: ubuntu-latest
    steps:
      - id: up
        uses: actions/upload-artifact@v4
        with: { path: x }
`);
    const failures = runGuard({
      readdirSync: () => ["t.yml"],
      readFileSync: (p) => {
        if (String(p).endsWith("playwright-upload-manifest.json")) {
          return JSON.stringify([]);
        }
        return workflow;
      },
    });
    assert.ok(failures.some((m) => /not in the manifest/.test(m)));
  });

  it("fails a stale manifest entry with no matching discovered step", () => {
    const workflow = yamlFor(`
  j:
    runs-on: ubuntu-latest
    steps:
      - run: echo hi
`);
    const failures = runGuard({
      readdirSync: () => ["t.yml"],
      readFileSync: (p) => {
        if (String(p).endsWith("playwright-upload-manifest.json")) {
          return JSON.stringify([
            {
              workflow: ".github/workflows/t.yml",
              job: "j",
              stepId: "up",
              playwright: false,
            },
          ]);
        }
        return workflow;
      },
    });
    assert.ok(failures.some((m) => /no longer matches/.test(m)));
  });

  it("passes a playwright:false entry with no sanitizer wiring", () => {
    const workflow = yamlFor(`
  j:
    runs-on: ubuntu-latest
    steps:
      - id: up
        uses: actions/upload-artifact@v4
        with: { path: x }
`);
    const failures = runGuard({
      readdirSync: () => ["t.yml"],
      readFileSync: (p) => {
        if (String(p).endsWith("playwright-upload-manifest.json")) {
          return JSON.stringify([
            {
              workflow: ".github/workflows/t.yml",
              job: "j",
              stepId: "up",
              playwright: false,
            },
          ]);
        }
        return workflow;
      },
    });
    assert.deepEqual(failures, []);
  });

  it("passes a credential-using job that has neither a sanitizer nor an upload-artifact step", () => {
    const workflow = yamlFor(`
  j:
    runs-on: ubuntu-latest
    env:
      DEMO_PASSWORD: \${{ secrets.E2E_TEST_USER_PASSWORD }}
    steps:
      - run: echo verify only
`);
    const failures = runGuard({
      readdirSync: () => ["t.yml"],
      readFileSync: (p) => {
        if (String(p).endsWith("playwright-upload-manifest.json")) {
          return JSON.stringify([]);
        }
        return workflow;
      },
    });
    assert.deepEqual(failures, []);
  });

  it("fails an upload whose predecessor and path are correct but whose if omits the gate", () => {
    const workflow = yamlFor(`
  j:
    runs-on: ubuntu-latest
    steps:
      - id: sanitize
        if: failure()
        uses: ./.github/actions/sanitize-playwright-artifacts
        with:
          mode: fallback-only
          source-dir: a
          staging-dir: /tmp/a
      - id: up
        if: failure()
        uses: actions/upload-artifact@v4
        with: { path: /tmp/a }
`);
    const failures = runGuard({
      readdirSync: () => ["t.yml"],
      readFileSync: (p) => {
        if (String(p).endsWith("playwright-upload-manifest.json")) {
          return JSON.stringify([
            {
              workflow: ".github/workflows/t.yml",
              job: "j",
              stepId: "up",
              playwright: true,
              baseline: "failure",
            },
          ]);
        }
        return workflow;
      },
    });
    assert.ok(failures.some((m) => /upload 'if:' is not exactly/.test(m)));
  });
});
