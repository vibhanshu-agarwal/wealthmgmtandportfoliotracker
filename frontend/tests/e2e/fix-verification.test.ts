/**
 * Fix Verification & Preservation Tests — Phase 1 Bugfix
 *
 * Validates that:
 * 1. The entrypoint guard now invokes globalSetup() on direct execution (fix works)
 * 2. Importing the module does NOT auto-invoke globalSetup() (Playwright preserved)
 * 3. deploy-azure.yml has the correct job dependency graph
 * 4. ci-verification.yml no longer contains the Azure seed step
 *
 * Run with:
 *   npx ts-node --project tests/e2e/tsconfig.e2e-test.json tests/e2e/fix-verification.test.ts
 *
 * **Validates: Correctness Properties 1 (Bug Condition) and 2 (Preservation)**
 */

import { spawnSync } from "node:child_process";
import path from "node:path";
import fs from "node:fs";

const FRONTEND_DIR = path.resolve(__dirname, "../..");
const REPO_ROOT = path.resolve(FRONTEND_DIR, "..");

let passed = 0;
let failed = 0;
let skipped = 0;

function check(condition: boolean, message: string): void {
  if (condition) {
    console.log(`  ✓ ${message}`);
    passed++;
  } else {
    console.error(`  ✗ ${message}`);
    failed++;
  }
}

function skip(message: string, reason: string): void {
  console.log(`  ⊘ ${message} [SKIPPED: ${reason}]`);
  skipped++;
}

// ─────────────────────────────────────────────────────────────────────────────
// 8.1: Entrypoint guard invokes globalSetup() on direct execution (fix check)
// ─────────────────────────────────────────────────────────────────────────────
console.log("\n8.1 — Entrypoint guard invokes globalSetup() on direct execution");
console.log("─".repeat(60));

(() => {
  // Run global-setup.ts directly using the CommonJS tsconfig (tsconfig.e2e-test.json).
  // This matches the updated CI seed command. With the fix in place, the
  // require.main === module guard fires and calls globalSetup().
  //
  // Since INTERNAL_API_KEY is set and SKIP_GOLDEN_STATE_SEEDING is not "true",
  // runSeeding() will log "Starting Golden State seeding..." before attempting
  // network calls. We point at a non-routable address so it fails fast.
  const env = {
    ...process.env,
    NEXT_PUBLIC_API_BASE_URL: "http://127.0.0.1:1",
    INTERNAL_API_KEY: "test-key",
    SKIP_BACKEND_HEALTH_CHECK: "true",
    E2E_TEST_USER_ID: "00000000-0000-0000-0000-000000000e2e",
    // Reduce retry/timeout budget so the test fails fast
    SEED_MAX_RETRIES: "1",
    SEED_RETRY_DELAY_MS: "100",
    SEED_REQUEST_TIMEOUT_MS: "3000",
    SEED_WARMUP_TIMEOUT_MS: "2000",
    HEALTH_CHECK_TIMEOUT_MS: "5000",
    NODE_ENV: "test" as const,
  };

  const result = spawnSync(
    "npx",
    ["ts-node", "--project", "tests/e2e/tsconfig.e2e-test.json", "tests/e2e/global-setup.ts"],
    {
      cwd: FRONTEND_DIR,
      env,
      encoding: "utf-8",
      timeout: 60_000,
      stdio: ["pipe", "pipe", "pipe"],
      shell: true,
    },
  );

  const combinedOutput = `${result.stdout ?? ""}\n${result.stderr ?? ""}`;

  // The fix means globalSetup() IS called. It will log the seeding marker
  // before attempting (and failing) network calls.
  const seedingMarker = "Starting Golden State seeding";
  const warmupMarker = "Warming backend seed dependencies";

  check(
    combinedOutput.includes(seedingMarker) || combinedOutput.includes(warmupMarker),
    `globalSetup() was invoked (output contains seeding markers)`,
  );

  // The process should exit non-zero because the seed fetch will fail
  // (non-routable address with minimal retry budget). This proves error propagation works.
  check(
    result.status !== 0,
    `Process exited non-zero (${result.status}) — error propagation works`,
  );
})();

// ─────────────────────────────────────────────────────────────────────────────
// 8.2: Importing the module does NOT auto-invoke globalSetup() (preservation)
// ─────────────────────────────────────────────────────────────────────────────
console.log("\n8.2 — Module import does NOT auto-invoke globalSetup()");
console.log("─".repeat(60));

(() => {
  // When Playwright (or any other importer) loads global-setup.ts via require(),
  // the require.main guard should NOT fire because require.main !== module.
  // We verify this by requiring the module in a child process and checking
  // that no seeding occurs.

  // The global-setup-export.test.ts already validates the export contract
  // (it requires the module and asserts the default export is a function).
  // Here we run it and confirm it passes — which proves no side effects on import.
  const result = spawnSync(
    "npx",
    ["ts-node", "--project", "tests/e2e/tsconfig.e2e-test.json", "tests/e2e/global-setup-export.test.ts"],
    {
      cwd: FRONTEND_DIR,
      env: {
        ...process.env,
        INTERNAL_API_KEY: "test-key",
        NEXT_PUBLIC_API_BASE_URL: "http://127.0.0.1:1",
        SKIP_BACKEND_HEALTH_CHECK: "true",
        E2E_TEST_USER_ID: "00000000-0000-0000-0000-000000000e2e",
        NODE_ENV: "test" as const,
      },
      encoding: "utf-8",
      timeout: 30_000,
      stdio: ["pipe", "pipe", "pipe"],
      shell: true,
    },
  );

  const combinedOutput = `${result.stdout ?? ""}\n${result.stderr ?? ""}`;

  // The export test should pass (exit 0) — proving the module can be imported
  // without triggering globalSetup()
  check(
    result.status === 0,
    "global-setup-export.test.ts passes (module importable without side effects)",
  );

  // Additionally verify no seeding markers appear in the output
  const seedingMarker = "Starting Golden State seeding";
  check(
    !combinedOutput.includes(seedingMarker),
    "No seeding markers in output (globalSetup() not auto-invoked on import)",
  );
})();

// ─────────────────────────────────────────────────────────────────────────────
// 8.3: deploy-azure.yml job dependency graph is correct
// ─────────────────────────────────────────────────────────────────────────────
console.log("\n8.3 — deploy-azure.yml job dependency graph");
console.log("─".repeat(60));

(() => {
  const workflowPath = path.join(REPO_ROOT, ".github/workflows/deploy-azure.yml");

  if (!fs.existsSync(workflowPath)) {
    check(false, `deploy-azure.yml not found at ${workflowPath}`);
    return;
  }

  const content = fs.readFileSync(workflowPath, "utf-8");

  // Check deploy-frontend needs includes deploy
  const deployFrontendNeeds = content.match(/deploy-frontend:[\s\S]*?needs:\s*\[([^\]]+)\]/);
  if (deployFrontendNeeds) {
    const needs = deployFrontendNeeds[1];
    check(
      needs.includes("preflight") && needs.includes("deploy"),
      `deploy-frontend needs: [${needs.trim()}] includes preflight and deploy`,
    );
  } else {
    check(false, "Could not find deploy-frontend needs declaration");
  }

  // Check deploy-frontend gate does NOT contain always()
  const deployFrontendIf = content.match(/deploy-frontend:[\s\S]*?if:\s*(.+)/);
  if (deployFrontendIf) {
    const gate = deployFrontendIf[1];
    check(!gate.includes("always()"), "deploy-frontend gate does NOT contain always()");
    check(
      gate.includes("needs.deploy.result == 'success'"),
      "deploy-frontend gate requires needs.deploy.result == 'success'",
    );
  } else {
    check(false, "Could not find deploy-frontend if: gate");
  }

  // Check seed job exists with correct needs
  const seedNeeds = content.match(/\bseed:[\s\S]*?needs:\s*\[([^\]]+)\]/);
  if (seedNeeds) {
    const needs = seedNeeds[1];
    check(
      needs.includes("preflight") && needs.includes("deploy") && needs.includes("deploy-frontend"),
      `seed needs: [${needs.trim()}] includes preflight, deploy, deploy-frontend`,
    );
  } else {
    check(false, "Could not find seed job needs declaration");
  }

  // Check verify job exists with correct needs
  const verifyNeeds = content.match(/\bverify:[\s\S]*?needs:\s*\[([^\]]+)\]/);
  if (verifyNeeds) {
    const needs = verifyNeeds[1];
    check(
      needs.includes("preflight") && needs.includes("seed"),
      `verify needs: [${needs.trim()}] includes preflight and seed`,
    );
  } else {
    check(false, "Could not find verify job needs declaration");
  }

  // Check seed job uses the correct tsconfig (CommonJS e2e tsconfig)
  check(
    content.includes("npx ts-node --project tests/e2e/tsconfig.e2e-test.json tests/e2e/global-setup.ts"),
    "seed job uses CommonJS tsconfig: tests/e2e/tsconfig.e2e-test.json",
  );

  // Check verify job runs the verification script
  check(
    content.includes("bash .github/workflows/scripts/verify-azure-demo.sh"),
    "verify job runs bash .github/workflows/scripts/verify-azure-demo.sh",
  );
})();

// ─────────────────────────────────────────────────────────────────────────────
// 8.4: ci-verification.yml has no Azure seed step
// ─────────────────────────────────────────────────────────────────────────────
console.log("\n8.4 — ci-verification.yml has no Azure seed step");
console.log("─".repeat(60));

(() => {
  const workflowPath = path.join(REPO_ROOT, ".github/workflows/ci-verification.yml");

  if (!fs.existsSync(workflowPath)) {
    check(false, `ci-verification.yml not found at ${workflowPath}`);
    return;
  }

  const content = fs.readFileSync(workflowPath, "utf-8");

  check(
    !content.includes("Seed live Azure environment"),
    'No step named "Seed live Azure environment" exists',
  );

  check(
    !content.includes("Seed live Azure"),
    'No reference to "Seed live Azure" exists anywhere in the file',
  );

  // Verify the AWS steps are still present (preservation)
  check(
    content.includes("Pre-warm AWS Lambda stack") || content.includes("aws-synthetic"),
    "AWS-related steps are still present (preservation)",
  );
})();

// ─────────────────────────────────────────────────────────────────────────────
// Summary
// ─────────────────────────────────────────────────────────────────────────────
console.log("\n" + "═".repeat(60));
console.log(`Results: ${passed} passed, ${failed} failed, ${skipped} skipped`);
console.log("═".repeat(60));

if (failed > 0) {
  process.exit(1);
}
