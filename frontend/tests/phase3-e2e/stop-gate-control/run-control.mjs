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
 * runner against a local origin and checking which scenarios actually ran.
 *
 * Three arms, because a one-armed control proves nothing — an arm that reports "S02 did not
 * run" is worthless unless another arm shows S02 CAN run in the same harness:
 *
 *   ungated  maxFailures off, ids differ  -> S00 fails and S02 RUNS      (the exposure)
 *   gated    maxFailures 1,  ids differ   -> S00 fails and S02 does NOT  (the gate)
 *   matching maxFailures 1,  ids equal    -> both pass, S02 RUNS         (no false stop)
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

function fail(message) {
  process.stderr.write(`stop-gate control: ${message}\n`);
  process.exitCode = 1;
}

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

function runArm({ origin, expectedBuildId, maxFailures, markerDir }) {
  const marker = path.join(markerDir, "s02-started.marker");
  fs.rmSync(marker, { force: true });
  const cli = path.join(FRONTEND, "node_modules", "@playwright", "test", "cli.js");
  const result = spawnSyncCapture(process.execPath, [cli, "test", "-c", path.join(HERE, "control.config.ts")], {
    cwd: FRONTEND,
    env: {
      ...process.env,
      CONTROL_ORIGIN: origin,
      CONTROL_EXPECTED_BUILD_ID: expectedBuildId,
      CONTROL_MARKER: marker,
      CONTROL_MAX_FAILURES: String(maxFailures),
      CI: "1",
    },
  });
  return { s02Ran: fs.existsSync(marker), output: result };
}

function spawnSyncCapture(command, args, options) {
  const r = spawnSync(command, args, { ...options, encoding: "utf-8" });
  return `${r.stdout ?? ""}${r.stderr ?? ""}`;
}

async function main() {
  const markerDir = fs.mkdtempSync(path.join(os.tmpdir(), "stop-gate-control-"));
  let origin;
  try {
    origin = await startOrigin();
    const base = `http://127.0.0.1:${origin.port}`;

    // Arm 1 — the exposure. Without the gate, S02 runs after S00 fails.
    const ungated = runArm({
      origin: base, expectedBuildId: OTHER_BUILD_ID, maxFailures: 0, markerDir,
    });
    if (!ungated.s02Ran) {
      fail(
        "ungated arm did not reach S02, so this harness cannot detect the gate failing. " +
        "Fix the harness before trusting the gated arm.\n" + ungated.output,
      );
      return;
    }

    // Arm 2 — the gate. The same mismatch must stop the run before S02.
    const gated = runArm({
      origin: base, expectedBuildId: OTHER_BUILD_ID, maxFailures: 1, markerDir,
    });
    if (gated.s02Ran) {
      fail("GATE FAILED: S02 ran after a build-id mismatch\n" + gated.output);
      return;
    }
    if (!/did not run/.test(gated.output)) {
      fail(
        "gated arm stopped without Playwright reporting a scenario that 'did not run'; " +
        "the run may have ended for some other reason\n" + gated.output,
      );
      return;
    }

    // Arm 3 — no false stop. A matching build id must let the run continue.
    const matching = runArm({
      origin: base, expectedBuildId: SERVED_BUILD_ID, maxFailures: 1, markerDir,
    });
    if (!matching.s02Ran) {
      fail("gate is over-strict: S02 did not run even though the served build matched\n" + matching.output);
      return;
    }

    process.stdout.write(
      "stop-gate control passed: ungated reached S02, the gate stopped it on a mismatch, " +
      "and a matching build id did not stop it.\n",
    );
  } catch (error) {
    fail(String(error));
  } finally {
    if (origin?.child && origin.child.exitCode === null) origin.child.kill("SIGKILL");
    fs.rmSync(markerDir, { recursive: true, force: true });
  }
}

await main();
