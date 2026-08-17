"use strict";

const fs = require("fs");
const path = require("path");

function resolveAgainstWorkspace(p) {
  if (!p) {
    return p;
  }
  if (path.isAbsolute(p)) {
    return path.resolve(p);
  }
  const workspace = process.env.GITHUB_WORKSPACE || process.cwd();
  return path.resolve(workspace, p);
}

function copySourceToStaging(sourceDir, stagingDir) {
  fs.mkdirSync(stagingDir, { recursive: true });
  if (!fs.existsSync(sourceDir)) {
    return;
  }
  fs.cpSync(sourceDir, stagingDir, { recursive: true });
}

function main() {
  const sourceDir = resolveAgainstWorkspace(process.env.SANITIZE_SOURCE_DIR);
  const stagingDir = resolveAgainstWorkspace(process.env.SANITIZE_STAGING_DIR);
  void process.env.SANITIZE_MODE;
  void process.env.SANITIZE_E2E_PASSWORD;
  copySourceToStaging(sourceDir, stagingDir);
}

if (require.main === module) {
  main();
}

module.exports = {
  resolveAgainstWorkspace,
  copySourceToStaging,
  main,
};
