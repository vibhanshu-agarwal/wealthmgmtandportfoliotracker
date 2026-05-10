/**
 * Bug Condition Exploration Test — Defect 1
 *
 * Confirms that running `global-setup.ts` directly via ts-node does NOT
 * invoke `globalSetup()`. The module ends with `export default globalSetup;`
 * but has no `require.main === module` guard or top-level invocation, so
 * direct execution loads the module, evaluates the export, and exits 0
 * without ever calling globalSetup() (which would log "Starting Golden State
 * seeding...").
 *
 * This test PASSES on unfixed code (confirming the bug exists).
 *
 * **Validates: Requirements 1.1**
 *
 * Run with:
 *   npx ts-node --project tsconfig.json tests/e2e/global-setup-entrypoint.test.ts
 */

import { execSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const FRONTEND_DIR = path.resolve(__dirname, "../..");

function runExplorationTest(): void {
  console.log("=== Bug Condition Exploration Test: global-setup.ts entrypoint ===\n");

  // Spawn the global-setup.ts script directly via ts-node, exactly as CI does.
  // We point at a non-existent server so that if globalSetup() WERE called,
  // it would attempt network calls and log "Starting Golden State seeding".
  const env = {
    ...process.env,
    NEXT_PUBLIC_API_BASE_URL: "http://localhost:9999",
    INTERNAL_API_KEY: "test-key",
    SKIP_BACKEND_HEALTH_CHECK: "true",
    E2E_TEST_USER_ID: "00000000-0000-0000-0000-000000000e2e",
  };

  let stdout = "";
  let stderr = "";
  let exitCode: number | null = 0;

  try {
    stdout = execSync(
      "npx ts-node --project tsconfig.json tests/e2e/global-setup.ts",
      {
        cwd: FRONTEND_DIR,
        env,
        encoding: "utf-8",
        timeout: 30_000,
        stdio: ["pipe", "pipe", "pipe"],
      },
    );
  } catch (error: unknown) {
    // execSync throws on non-zero exit code
    const execError = error as { status: number | null; stdout: string; stderr: string };
    exitCode = execError.status;
    stdout = execError.stdout ?? "";
    stderr = execError.stderr ?? "";
  }

  const combinedOutput = `${stdout}\n${stderr}`;

  console.log(`Exit code: ${exitCode}`);
  console.log(`stdout: ${stdout || "<empty>"}`);
  if (stderr) {
    console.log(`stderr (first 300 chars): ${stderr.slice(0, 300)}`);
  }
  console.log("");

  // Assertion 1: Process exits with code 0 (no error — it just loads and exits)
  let passed = true;

  if (exitCode !== 0) {
    console.error("FAIL: Expected exit code 0, got", exitCode);
    console.error("  This means the module errored on load, which is unexpected.");
    passed = false;
  } else {
    console.log("PASS: Process exited with code 0 (module loaded and exited cleanly).");
  }

  // Assertion 2: Output does NOT contain "Starting Golden State seeding"
  // If globalSetup() were called, runSeeding() would log this message
  // (since INTERNAL_API_KEY is set and SKIP_GOLDEN_STATE_SEEDING is not "true").
  const seedingMarker = "Starting Golden State seeding";
  if (combinedOutput.includes(seedingMarker)) {
    console.error(`FAIL: Output contains "${seedingMarker}" — globalSetup() was called!`);
    console.error("  The bug may have already been fixed.");
    passed = false;
  } else {
    console.log(`PASS: Output does NOT contain "${seedingMarker}" — globalSetup() was never called.`);
    console.log("  This confirms the bug: direct ts-node execution is a no-op.");
  }

  // Assertion 3: Output does NOT contain "Warming backend seed dependencies"
  // This is another marker from within runSeeding() → warmSeedDependencies()
  const warmupMarker = "Warming backend seed dependencies";
  if (combinedOutput.includes(warmupMarker)) {
    console.error(`FAIL: Output contains "${warmupMarker}" — seeding logic executed!`);
    passed = false;
  } else {
    console.log(`PASS: Output does NOT contain "${warmupMarker}".`);
  }

  console.log("");
  if (passed) {
    console.log("✓ BUG CONDITION CONFIRMED: global-setup.ts exits without invoking globalSetup().");
    console.log("  The module loads, evaluates `export default globalSetup;`, and exits 0.");
    console.log("  No seeding occurs. This is the defect described in bugfix.md §1.1.");
    process.exit(0);
  } else {
    console.error("✗ BUG CONDITION NOT CONFIRMED: One or more assertions failed.");
    console.error("  The bug may have already been fixed, or the test environment is misconfigured.");
    process.exit(1);
  }
}

runExplorationTest();
