"use strict";

const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const { validateManifestTracePath } = require("./sanitize.js");

function repoRootFromHere() {
  return path.resolve(__dirname, "../../..");
}

function defaultLockfilePath() {
  return path.join(repoRootFromHere(), "frontend", "package-lock.json");
}

function lockfileVersions(lockfilePath) {
  const lock = JSON.parse(fs.readFileSync(lockfilePath, "utf8"));
  const pkgs = lock.packages || {};
  const testVer = pkgs["node_modules/@playwright/test"]?.version;
  const pwVer = pkgs["node_modules/playwright"]?.version;
  const coreVer = pkgs["node_modules/playwright-core"]?.version;
  if (!testVer || !pwVer || !coreVer) {
    throw new Error("lockfile is missing Playwright package versions");
  }
  return { testVer, pwVer, coreVer };
}

function walkTraceFiles(playwrightReportDir) {
  const traceDir = path.join(playwrightReportDir, "trace");
  const files = [];
  if (!fs.existsSync(traceDir)) {
    throw new Error(`missing trace directory: ${traceDir}`);
  }
  const stack = [traceDir];
  while (stack.length) {
    const dir = stack.pop();
    for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, ent.name);
      if (ent.isDirectory()) stack.push(full);
      else if (ent.isFile()) files.push(full);
    }
  }
  files.sort();
  return files;
}

function fileSha256(filePath) {
  const hash = crypto.createHash("sha256");
  const data = fs.readFileSync(filePath);
  hash.update(data);
  return hash.digest("hex");
}

function deriveAssets(playwrightReportDir) {
  const assets = [];
  for (const filePath of walkTraceFiles(playwrightReportDir)) {
    const rel = path
      .relative(playwrightReportDir, filePath)
      .split(path.sep)
      .join("/");
    assets.push({ path: rel, sha256: fileSha256(filePath) });
  }
  return assets;
}

function validateManifestSchema(manifest, stagingDirRoot) {
  if (!manifest || typeof manifest !== "object" || Array.isArray(manifest)) {
    throw new Error("manifest must be a JSON object");
  }
  const keys = Object.keys(manifest).sort();
  if (keys.join(",") !== "assets,playwrightTestVersion") {
    throw new Error(`manifest has unexpected/missing fields: ${keys.join(",")}`);
  }
  if (typeof manifest.playwrightTestVersion !== "string") {
    throw new Error("playwrightTestVersion must be a string");
  }
  if (!Array.isArray(manifest.assets)) {
    throw new Error("assets must be an array");
  }
  const root = stagingDirRoot || path.resolve("/staging");
  const seen = new Set();
  for (const asset of manifest.assets) {
    const assetKeys = Object.keys(asset).sort().join(",");
    if (assetKeys !== "path,sha256") {
      throw new Error(`asset has unexpected/missing fields: ${assetKeys}`);
    }
    if (validateManifestTracePath(asset.path, root) !== asset.path) {
      throw new Error(`non-canonical asset path: ${asset.path}`);
    }
    if (!/^[0-9a-f]{64}$/.test(asset.sha256)) {
      throw new Error(`malformed digest for ${asset.path}`);
    }
    if (seen.has(asset.path)) {
      throw new Error(`duplicate asset path: ${asset.path}`);
    }
    seen.add(asset.path);
  }
}

function generateManifest(playwrightReportDir, manifestPath, options = {}) {
  const lockfilePath = options.lockfilePath || defaultLockfilePath();
  const { testVer, pwVer, coreVer } = lockfileVersions(lockfilePath);
  if (testVer !== pwVer || testVer !== coreVer) {
    throw new Error(
      `Playwright package versions diverge: test=${testVer} playwright=${pwVer} core=${coreVer}`,
    );
  }
  const assets = deriveAssets(playwrightReportDir);
  const manifest = { playwrightTestVersion: testVer, assets };
  validateManifestSchema(manifest);
  fs.writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);
  return manifest;
}

function assetKeySet(assets) {
  return new Set(assets.map((a) => `${a.path}:${a.sha256}`));
}

function verifyManifest(playwrightReportDir, manifestPath, options = {}) {
  const lockfilePath = options.lockfilePath || defaultLockfilePath();
  const { testVer, pwVer, coreVer } = lockfileVersions(lockfilePath);
  if (testVer !== pwVer || testVer !== coreVer) {
    throw new Error(
      `Playwright package versions diverge: test=${testVer} playwright=${pwVer} core=${coreVer}`,
    );
  }
  const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
  validateManifestSchema(manifest);
  if (manifest.playwrightTestVersion !== testVer) {
    throw new Error(
      `playwrightTestVersion ${manifest.playwrightTestVersion} != lockfile ${testVer}`,
    );
  }
  const actual = deriveAssets(playwrightReportDir);
  const expectedKeys = assetKeySet(manifest.assets);
  const actualKeys = assetKeySet(actual);
  for (const key of actualKeys) {
    if (!expectedKeys.has(key)) {
      throw new Error(`producer asset missing or digest-mismatched in manifest: ${key}`);
    }
  }
  for (const key of expectedKeys) {
    if (!actualKeys.has(key)) {
      throw new Error(`manifest asset not present in producer output: ${key}`);
    }
  }
}

function main(argv = process.argv.slice(2)) {
  const [mode, reportDir, manifestPath] = argv;
  try {
    if (mode === "--generate") {
      generateManifest(reportDir, manifestPath);
    } else if (mode === "--verify") {
      verifyManifest(reportDir, manifestPath);
    } else {
      throw new Error("usage: known-assets-manifest-tool.js --generate|--verify <reportDir> <manifest>");
    }
  } catch (err) {
    console.error(`::error::${err.message}`);
    process.exit(1);
  }
}

if (require.main === module) {
  main();
}

module.exports = {
  generateManifest,
  verifyManifest,
  validateManifestSchema,
  deriveAssets,
  lockfileVersions,
  main,
};
