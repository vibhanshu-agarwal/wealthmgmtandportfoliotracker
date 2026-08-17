"use strict";

const fs = require("fs");
const path = require("path");
const yaml = require("js-yaml");

const SANITIZER_ACTION_PATH = "./.github/actions/sanitize-playwright-artifacts";
const WORKFLOW_PATH_PATTERN = /^\.github\/workflows\/[^/\\]+\.ya?ml$/;
const SECRET_EXPR =
  /secrets\s*(?:\.\s*E2E_TEST_USER_PASSWORD\b|\[\s*['"]E2E_TEST_USER_PASSWORD['"]\s*\])/i;
const EXACT_SECRET_EXPRESSION = "${{ secrets.E2E_TEST_USER_PASSWORD }}";
const ROOT = path.resolve(__dirname, "..");
const MANIFEST_PATH = path.join(ROOT, "scripts", "playwright-upload-manifest.json");

const failures = [];

function fail(msg) {
  failures.push(msg);
}

function resetFailures() {
  failures.length = 0;
}

function getFailures() {
  return [...failures];
}

function isCanonicalWorkflowPath(s) {
  return typeof s === "string" && WORKFLOW_PATH_PATTERN.test(s);
}

function usesSanitizerAction(step) {
  if (!step?.uses) return false;
  return step.uses.replace(/\/+$/, "") === SANITIZER_ACTION_PATH;
}

function listWorkflowFiles(readdirSync = fs.readdirSync) {
  const workflowDir = path.join(ROOT, ".github", "workflows");
  const basenames = readdirSync(workflowDir).filter((f) => /\.ya?ml$/.test(f));
  const result = [];
  for (const basename of basenames) {
    const key = `.github/workflows/${basename}`;
    if (!isCanonicalWorkflowPath(key)) {
      fail(
        `discovered workflow entry does not produce a canonical path: ${JSON.stringify(basename)}`,
      );
      continue;
    }
    result.push(key);
  }
  return result;
}

function validateManifestSchema(manifest) {
  if (!Array.isArray(manifest)) {
    fail("manifest must be a top-level JSON array");
    return;
  }
  const seen = new Set();
  for (const entry of manifest) {
    const keys = Object.keys(entry).sort().join(",");
    const isPlaywrightShape = keys === "baseline,job,playwright,stepId,workflow";
    const isNonPlaywrightShape = keys === "job,playwright,stepId,workflow";
    if (!isPlaywrightShape && !isNonPlaywrightShape) {
      fail(`manifest entry has unexpected/missing fields: ${JSON.stringify(entry)}`);
      continue;
    }
    if (!isCanonicalWorkflowPath(entry.workflow)) {
      fail(
        `manifest entry's workflow path is not canonical (.github/workflows/<file>.yml, no '..'/drive/backslash/double-slash): ${JSON.stringify(entry)}`,
      );
    }
    if (typeof entry.job !== "string" || entry.job === "") {
      fail(`manifest entry has an empty/invalid job id: ${JSON.stringify(entry)}`);
    }
    if (typeof entry.stepId !== "string" || entry.stepId === "") {
      fail(`manifest entry has an empty/invalid stepId: ${JSON.stringify(entry)}`);
    }
    if (typeof entry.playwright !== "boolean") {
      fail(
        `manifest entry's 'playwright' field must be a literal boolean, got ${JSON.stringify(entry.playwright)}: ${JSON.stringify(entry)}`,
      );
    }
    if (
      entry.playwright === true &&
      entry.baseline !== "always" &&
      entry.baseline !== "failure"
    ) {
      fail(
        `manifest entry classified playwright:true must have baseline exactly 'always' or 'failure': ${JSON.stringify(entry)}`,
      );
    }
    if (entry.playwright === false && isPlaywrightShape) {
      fail(
        `manifest entry classified playwright:false must not carry a baseline field: ${JSON.stringify(entry)}`,
      );
    }
    const key = `${entry.workflow}:${entry.job}:${entry.stepId}`;
    if (seen.has(key)) fail(`manifest contains a duplicate/conflicting entry for ${key}`);
    seen.add(key);
  }
}

function validateNoDuplicateDiscovered(discovered) {
  const seen = new Set();
  for (const d of discovered) {
    const key = `${d.workflow}:${d.job}:${d.stepId}`;
    if (seen.has(key)) {
      fail(`discovered a duplicate upload-artifact step id within one job: ${key}`);
    }
    seen.add(key);
  }
}

function normalizeExpr(s) {
  return (s ?? "").replace(/\s+/g, " ").trim();
}

function isProvenConjunctiveGate(ifExpr, predecessorId, expectedBaseline) {
  const gate = `steps.${predecessorId}.outcome == 'success'`;
  const expected = normalizeExpr(`${expectedBaseline}() && ${gate}`);
  return normalizeExpr(ifExpr) === expected;
}

function independentJobView(job) {
  return { ...job, steps: (job.steps ?? []).filter((s) => !usesSanitizerAction(s)) };
}

function referencesSecretIndependently(job) {
  const seen = new WeakSet();
  function walk(node) {
    if (node === null || typeof node !== "object") {
      return typeof node === "string" && SECRET_EXPR.test(node);
    }
    if (seen.has(node)) return false;
    seen.add(node);
    return Object.values(node).some(walk);
  }
  return walk(independentJobView(job));
}

function jobHasUploadArtifact(job) {
  return (job.steps ?? []).some((step) =>
    (step.uses ?? "").startsWith("actions/upload-artifact@"),
  );
}

function checkSecretMode(allParsedJobs) {
  for (const [jobId, job] of allParsedJobs) {
    const sanitizeSteps = (job.steps ?? []).filter(usesSanitizerAction);
    if (sanitizeSteps.length > 1) {
      fail(
        `${jobId}: job has ${sanitizeSteps.length} sanitizer steps; this design supports at most one per job`,
      );
      continue;
    }
    const sanitizer = sanitizeSteps[0];
    const independent = referencesSecretIndependently(job);
    if (independent && (sanitizer || jobHasUploadArtifact(job))) {
      const correctlyWired =
        sanitizer &&
        sanitizer.with?.mode === "live-secret" &&
        sanitizer.with?.["e2e-password"] === EXACT_SECRET_EXPRESSION;
      if (!correctlyWired) {
        fail(
          `${jobId}: job independently references the real secret but its sanitizer is missing, not 'live-secret', or does not use the exact e2e-password expression`,
        );
      }
    } else if (sanitizer) {
      const hasE2ePasswordKey =
        sanitizer.with != null &&
        Object.prototype.hasOwnProperty.call(sanitizer.with, "e2e-password");
      if (sanitizer.with?.mode !== "fallback-only" || hasE2ePasswordKey) {
        fail(
          `${jobId}: no independent secret usage, but the sanitizer is not exactly mode: fallback-only with e2e-password entirely absent`,
        );
      }
    }
  }
}

function discoverUploads(workflowFile, doc) {
  const discovered = [];
  for (const [jobId, job] of Object.entries(doc.jobs ?? {})) {
    for (const step of job.steps ?? []) {
      if ((step.uses ?? "").startsWith("actions/upload-artifact@")) {
        if (!step.id) {
          fail(`${workflowFile}:${jobId}: upload-artifact step has no id:`);
          continue;
        }
        discovered.push({
          workflow: workflowFile,
          job: jobId,
          stepId: step.id,
          step,
          jobObj: job,
        });
      }
    }
  }
  return discovered;
}

function checkUploadWiring(discovered, manifest) {
  for (const { workflow, job: jobId, stepId, step, jobObj } of discovered) {
    const classified = manifest.find(
      (e) => e.workflow === workflow && e.job === jobId && e.stepId === stepId,
    );
    if (!classified || !classified.playwright) continue;
    const steps = jobObj.steps;
    const i = steps.indexOf(step);
    const predecessor = steps[i - 1];
    if (!predecessor || !usesSanitizerAction(predecessor)) {
      fail(
        `${workflow}:${jobId}: Playwright upload '${stepId}' has no immediate sanitizer predecessor`,
      );
      continue;
    }
    if (step.with?.path !== predecessor.with?.["staging-dir"]) {
      fail(`${workflow}:${jobId}: upload path is not the sanitizer's staging-dir`);
    }
    if (!isProvenConjunctiveGate(step.if, predecessor.id, classified.baseline)) {
      fail(
        `${workflow}:${jobId}: upload 'if:' is not exactly '${classified.baseline}() && steps.${predecessor.id}.outcome == \\'success\\''`,
      );
    }
  }
}

function runGuard({
  readFileSync = fs.readFileSync,
  readdirSync = fs.readdirSync,
  manifestOverride,
} = {}) {
  resetFailures();
  const manifest = manifestOverride
    ? manifestOverride
    : JSON.parse(readFileSync(MANIFEST_PATH, "utf8"));
  validateManifestSchema(manifest);
  const manifestKeys = new Set(
    manifest.map((e) => `${e.workflow}:${e.job}:${e.stepId}`),
  );

  const discovered = [];
  const allParsedJobs = [];
  for (const workflowFile of listWorkflowFiles(readdirSync)) {
    const absWorkflow = path.isAbsolute(workflowFile)
      ? workflowFile
      : path.join(ROOT, workflowFile);
    const doc = yaml.load(readFileSync(absWorkflow, "utf8"), {
      maxTotalMergeKeys: 100,
    });
    for (const [jobId, job] of Object.entries(doc.jobs ?? {})) {
      allParsedJobs.push([`${workflowFile}:${jobId}`, job]);
    }
    discovered.push(...discoverUploads(workflowFile, doc));
  }
  validateNoDuplicateDiscovered(discovered);

  const discoveredKeys = new Set(
    discovered.map((d) => `${d.workflow}:${d.job}:${d.stepId}`),
  );
  for (const key of discoveredKeys) {
    if (!manifestKeys.has(key)) {
      fail(`Upload site ${key} is not in the manifest — classify it before merging`);
    }
  }
  for (const key of manifestKeys) {
    if (!discoveredKeys.has(key)) {
      fail(
        `Manifest entry ${key} no longer matches any discovered upload — remove or update it`,
      );
    }
  }

  checkUploadWiring(discovered, manifest);
  checkSecretMode(allParsedJobs);
  return getFailures();
}

if (require.main === module) {
  const found = runGuard();
  if (found.length) {
    for (const msg of found) console.error(`::error::${msg}`);
    process.exit(1);
  }
}

module.exports = {
  isCanonicalWorkflowPath,
  listWorkflowFiles,
  validateManifestSchema,
  validateNoDuplicateDiscovered,
  usesSanitizerAction,
  referencesSecretIndependently,
  isProvenConjunctiveGate,
  runGuard,
  fail,
  resetFailures,
  getFailures,
  EXACT_SECRET_EXPRESSION,
};
