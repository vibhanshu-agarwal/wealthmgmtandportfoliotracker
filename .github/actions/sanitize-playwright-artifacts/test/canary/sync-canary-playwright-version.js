"use strict";

const fs = require("fs");
const path = require("path");

const repoRoot = path.resolve(__dirname, "../../../../../");
const frontendLock = path.join(repoRoot, "frontend", "package-lock.json");
const canaryPkgPath = path.join(__dirname, "package.json");

function lockedPlaywrightTestVersion() {
  const lock = JSON.parse(fs.readFileSync(frontendLock, "utf8"));
  const version = lock.packages?.["node_modules/@playwright/test"]?.version;
  if (!version) {
    throw new Error("frontend/package-lock.json has no @playwright/test version");
  }
  return version;
}

function installedVersion(pkgName) {
  const pkgPath = path.join(__dirname, "node_modules", pkgName, "package.json");
  return JSON.parse(fs.readFileSync(pkgPath, "utf8")).version;
}

function writePin() {
  const version = lockedPlaywrightTestVersion();
  const pkg = JSON.parse(fs.readFileSync(canaryPkgPath, "utf8"));
  pkg.devDependencies = pkg.devDependencies || {};
  pkg.devDependencies["@playwright/test"] = version;
  fs.writeFileSync(canaryPkgPath, `${JSON.stringify(pkg, null, 2)}\n`);
}

function checkPin() {
  const expected = lockedPlaywrightTestVersion();
  const names = ["@playwright/test", "playwright", "playwright-core"];
  for (const name of names) {
    const actual = installedVersion(name);
    if (actual !== expected) {
      console.error(
        `::error::canary ${name} ${actual} != frontend lock ${expected}`,
      );
      process.exit(1);
    }
  }
}

const mode = process.argv[2];
if (mode === "--write") {
  writePin();
} else if (mode === "--check") {
  checkPin();
} else {
  console.error("usage: sync-canary-playwright-version.js --write|--check");
  process.exit(1);
}
