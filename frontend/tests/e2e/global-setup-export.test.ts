/**
 * Export-preservation test for global-setup.ts.
 *
 * Validates that the Playwright default-export contract is preserved after
 * adding the `require.main === module` entrypoint guard. This test can be
 * run standalone via `npx ts-node tests/e2e/global-setup-export.test.ts`
 * (it does NOT depend on vitest or any test runner).
 */

// eslint-disable-next-line @typescript-eslint/no-require-imports
const globalSetup = require("./global-setup").default;

let passed = 0;
let failed = 0;

function check(condition: boolean, message: string): void {
  if (condition) {
    console.log(`  ✓ ${message}`);
    passed++;
  } else {
    console.error(`  ✗ ${message}`);
    failed++;
  }
}

console.log("global-setup-export.test.ts");
console.log("──────────────────────────────────────────");

check(typeof globalSetup === "function", "default export is a function");
check(globalSetup.length === 0, "function has no required parameters (arity 0)");

console.log("──────────────────────────────────────────");
console.log(`Results: ${passed} passed, ${failed} failed`);

if (failed > 0) {
  process.exit(1);
}
