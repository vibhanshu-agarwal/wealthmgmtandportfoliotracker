/**
 * Production stop-gate control.
 *
 * playwright.phase3.config.ts sets `maxFailures: 1` for a Production run so that a failed
 * S00 — including a served build id that is not the one the deploy run uploaded — cannot be
 * followed by S02, which creates a PERMANENT Production account. The scenarios are NOT
 * serial-mode, so that setting is the only thing standing between the two.
 *
 * Static guards pin the setting. They cannot show it has any EFFECT: if a Playwright
 * upgrade changed what maxFailures does, every pin would stay green while S02 became
 * reachable again after a failed S00. This control closes that gap by running the real
 * runner against a local origin and checking what actually happened.
 *
 * The verdict comes from Playwright's JSON report and the process exit status — the
 * per-scenario outcome of every scenario, not a marker file and not reporter prose. A
 * marker alone is not evidence: a scenario can write its side effect and then fail, which
 * an oracle looking only for the marker would call a pass. The fourth arm injects exactly
 * that and requires this runner to reject it.
 *
 *   ungated   maxFailures off, ids differ  -> S00 fails, S02 RUNS AND PASSES  (the exposure)
 *   gated     maxFailures 1,  ids differ   -> S00 fails, S02 NEVER STARTS     (the gate)
 *   matching  maxFailures 1,  ids equal    -> both pass, exit 0               (no false stop)
 *   oracle    S02 writes its marker then FAILS -> the matching expectations must be violated
 *
 * Contacts nothing but 127.0.0.1, launches no browser, and always stops its server.
 */
import { spawn, spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FRONTEND = path.resolve(HERE, "../../..");
const SERVED_BUILD_ID = "controlServedBuildIdAAA";
const OTHER_BUILD_ID = "controlEmittedBuildIdBBB";
const S00 = "S00 preflight";
const S02 = "S02 successful signup";

const problems = [];
const note = (message) => problems.push(message);

function startOrigin() {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [path.join(HERE, "origin-server.mjs")], {
      cwd: FRONTEND,
      env: { ...process.env, CONTROL_SERVED_BUILD_ID: SERVED_BUILD_ID },
      stdio: ["ignore", "pipe", "pipe"],
    });
    let out = "";
    const timer = setTimeout(() => {
      child.kill("SIGKILL");
      reject(new Error("origin server did not report a port within 15s"));
    }, 15_000);
    child.stdout.on("data", (chunk) => {
      out += chunk.toString();
      const line = out.split("\n").find((l) => l.trim().startsWith("{"));
      if (!line) return;
      clearTimeout(timer);
      resolve({ child, port: JSON.parse(line).port });
    });
    child.on("exit", (code) => {
      clearTimeout(timer);
      reject(new Error(`origin server exited early with code ${code}`));
    });
  });
}

/** Flatten the JSON report to { <scenario>: { status, results: [...] } }. */
function outcomesOf(report) {
  const found = {};
  const walk = (suites) => {
    for (const suite of suites ?? []) {
      for (const spec of suite.specs ?? []) {
        for (const test of spec.tests ?? []) {
          const key = spec.title.startsWith(S00) ? S00 : spec.title.startsWith(S02) ? S02 : spec.title;
          found[key] = {
            status: test.status,
            results: (test.results ?? []).map((r) => r.status),
          };
        }
      }
      walk(suite.suites);
    }
  };
  walk(report.suites);
  return found;
}

function runArm({ origin, expectedBuildId, maxFailures, failS02AfterMarker, workDir }) {
  const marker = path.join(workDir, "s02-started.marker");
  const jsonOut = path.join(workDir, "report.json");
  fs.rmSync(marker, { force: true });
  fs.rmSync(jsonOut, { force: true });
  const cli = path.join(FRONTEND, "node_modules", "@playwright", "test", "cli.js");
  const run = spawnSync(process.execPath, [cli, "test", "-c", path.join(HERE, "control.config.ts")], {
    cwd: FRONTEND,
    encoding: "utf-8",
    env: {
      ...process.env,
      CONTROL_ORIGIN: origin,
      CONTROL_EXPECTED_BUILD_ID: expectedBuildId,
      CONTROL_MARKER: marker,
      CONTROL_MAX_FAILURES: String(maxFailures),
      CONTROL_JSON_OUT: jsonOut,
      CONTROL_FAIL_S02_AFTER_MARKER: failS02AfterMarker ? "1" : "0",
      CI: "1",
    },
  });
  const output = `${run.stdout ?? ""}${run.stderr ?? ""}`;
  const outcomes = fs.existsSync(jsonOut)
    ? outcomesOf(JSON.parse(fs.readFileSync(jsonOut, "utf-8")))
    : null;
  return { exitCode: run.status, outcomes, s02Ran: fs.existsSync(marker), output };
}

/** Everything about an arm that did not match what it claims. Empty means it held. */
function checkArm(label, actual, expected) {
  const faults = [];
  if (!actual.outcomes) {
    faults.push(`${label}: no JSON report was produced, so nothing can be asserted`);
    return faults;
  }
  if ((actual.exitCode === 0) !== expected.exitZero) {
    faults.push(
      `${label}: expected playwright to exit ${expected.exitZero ? "0" : "non-zero"}, got ${actual.exitCode}`,
    );
  }
  if (actual.s02Ran !== expected.s02Ran) {
    faults.push(`${label}: expected the S02 side effect to be ${expected.s02Ran ? "present" : "absent"}`);
  }
  for (const [scenario, want] of Object.entries(expected.scenarios)) {
    const got = actual.outcomes[scenario];
    if (!got) {
      faults.push(`${label}: ${scenario} is missing from the report`);
      continue;
    }
    if (got.status !== want.status) {
      faults.push(`${label}: ${scenario} status was ${got.status}, expected ${want.status}`);
    }
    if (got.results.join(",") !== want.results.join(",")) {
      faults.push(
        `${label}: ${scenario} results were [${got.results}], expected [${want.results}]`,
      );
    }
  }
  return faults;
}

const UNGATED = {
  exitZero: false,
  s02Ran: true,
  scenarios: {
    [S00]: { status: "unexpected", results: ["failed"] },
    [S02]: { status: "expected", results: ["passed"] },
  },
};
const GATED = {
  exitZero: false,
  s02Ran: false,
  // "skipped" with no results is how Playwright reports a scenario that never started.
  scenarios: {
    [S00]: { status: "unexpected", results: ["failed"] },
    [S02]: { status: "skipped", results: [] },
  },
};
const MATCHING = {
  exitZero: true,
  s02Ran: true,
  scenarios: {
    [S00]: { status: "expected", results: ["passed"] },
    [S02]: { status: "expected", results: ["passed"] },
  },
};

async function main() {
  const workDir = fs.mkdtempSync(path.join(os.tmpdir(), "stop-gate-control-"));
  let origin;
  try {
    origin = await startOrigin();
    const base = `http://127.0.0.1:${origin.port}`;
    const arm = (options) => runArm({ origin: base, workDir, failS02AfterMarker: false, ...options });

    // 1. The exposure. Without the gate, S02 runs to completion after S00 fails.
    const ungated = arm({ expectedBuildId: OTHER_BUILD_ID, maxFailures: 0 });
    const ungatedFaults = checkArm("ungated", ungated, UNGATED);
    if (ungatedFaults.length) {
      ungatedFaults.forEach(note);
      note("the harness cannot demonstrate the exposure, so the gated arm would prove nothing");
      note(ungated.output);
      return;
    }

    // 2. The gate. The same mismatch must stop the run before S02 starts.
    const gated = arm({ expectedBuildId: OTHER_BUILD_ID, maxFailures: 1 });
    const gatedFaults = checkArm("gated", gated, GATED);
    if (gatedFaults.length) {
      gatedFaults.forEach(note);
      note(gated.output);
      return;
    }

    // 3. No false stop. A matching build id must let the run finish normally.
    const matching = arm({ expectedBuildId: SERVED_BUILD_ID, maxFailures: 1 });
    const matchingFaults = checkArm("matching", matching, MATCHING);
    if (matchingFaults.length) {
      matchingFaults.forEach(note);
      note(matching.output);
      return;
    }

    // 4. The oracle's own negative control. S02 writes its side effect and then fails, so a
    //    marker-only check would call this a pass. Judged against the SAME expectations as
    //    arm 3: they must be violated, and the violation must name S02's outcome rather than
    //    only the exit status.
    const injected = arm({
      expectedBuildId: SERVED_BUILD_ID, maxFailures: 1, failS02AfterMarker: true,
    });
    const injectedFaults = checkArm("oracle-check", injected, MATCHING);
    if (!injected.s02Ran) {
      note("oracle-check: the injected arm did not write its marker, so it tests nothing");
    } else if (!injectedFaults.length) {
      note(
        "ORACLE TOO WEAK: a scenario that wrote its side effect and then FAILED was accepted " +
        "as a pass, so this control would not notice S02 failing after it had already acted.",
      );
      note(injected.output);
    } else if (!injectedFaults.some((fault) => fault.includes(S02))) {
      note(
        "oracle-check: the injected failure was rejected, but not because of S02's outcome: " +
        injectedFaults.join("; "),
      );
    }
  } catch (error) {
    note(String(error));
  } finally {
    if (origin?.child && origin.child.exitCode === null) origin.child.kill("SIGKILL");
    fs.rmSync(workDir, { recursive: true, force: true });
  }
}

await main();

if (problems.length) {
  for (const problem of problems) process.stderr.write(`stop-gate control: ${problem}\n`);
  process.exitCode = 1;
} else {
  process.stdout.write(
    "stop-gate control passed: ungated ran S02 to completion, the gate stopped the run before " +
    "S02 started, a matching build id did not stop it, and a scenario that failed after acting " +
    "was rejected rather than counted as a pass.\n",
  );
}
