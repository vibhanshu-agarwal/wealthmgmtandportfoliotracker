import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { provenanceEnvironment, scanArtifactsForSecrets } from "../artifacts";

const dirs: string[] = [];
function tempRunDir() {
  const dir = mkdtempSync(path.join(os.tmpdir(), "p3-artifacts-"));
  dirs.push(dir);
  return dir;
}

afterEach(() => {
  for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true });
});

describe("provenanceEnvironment", () => {
  it("records only allowlisted values and presence booleans for secrets", () => {
    const snapshot = provenanceEnvironment({
      P3_TARGET: "production",
      P3_IDENTITY_LIFECYCLE: "retained",
      P3_EMAIL_DOMAIN: "certs.example.org",
      P3_AUTH_MIN_INTERVAL_MS: "13000",
      P3_CERT_A_PASSWORD: "canary-password-aaaa",
      P3_CERT_B_PASSWORD: "canary-password-bbbb",
      P3_FRESH_PASSWORD: "canary-password-ffff",
      P3_PRODUCTION_APPROVAL: "owner-approved-phase3-production-run",
      P3_CERT_A_EMAIL: "cert-a@certs.example.org",
      INTERNAL_API_KEY: "canary-internal-key",
      UNRELATED_SECRET: "canary-unrelated",
    });
    const serialized = JSON.stringify(snapshot);
    for (const canary of ["canary-", "owner-approved", "cert-a@"]) expect(serialized).not.toContain(canary);
    expect(snapshot).toMatchObject({
      values: { P3_TARGET: "production", P3_IDENTITY_LIFECYCLE: "retained", P3_EMAIL_DOMAIN: "certs.example.org" },
      present: {
        P3_CERT_A_PASSWORD: true,
        P3_PRODUCTION_APPROVAL: true,
        INTERNAL_API_KEY: true,
        TF_VAR_internal_api_key: false,
      },
    });
    expect(Object.keys(snapshot.values)).not.toContain("UNRELATED_SECRET");
  });
});

describe("scanArtifactsForSecrets", () => {
  it("finds a secret in any text artifact outside pw-output", () => {
    const runDir = tempRunDir();
    writeFileSync(path.join(runDir, "ledger.jsonl"), '{"ok":true}\n');
    mkdirSync(path.join(runDir, "nested"));
    writeFileSync(path.join(runDir, "nested", "provenance.json"), '{"note":"x canary-secret-123 y"}');
    expect(scanArtifactsForSecrets(runDir, ["canary-secret-123"])).toEqual([
      { file: path.join("nested", "provenance.json") },
    ]);
  });

  it("ignores pw-output (declared sensitive) and binary screenshots", () => {
    const runDir = tempRunDir();
    mkdirSync(path.join(runDir, "pw-output"));
    writeFileSync(path.join(runDir, "pw-output", "trace.txt"), "canary-secret-123");
    mkdirSync(path.join(runDir, "screenshots"));
    writeFileSync(path.join(runDir, "screenshots", "S01.png"), Buffer.from("canary-secret-123"));
    expect(scanArtifactsForSecrets(runDir, ["canary-secret-123"])).toEqual([]);
  });

  it("reports nothing for a clean run directory", () => {
    const runDir = tempRunDir();
    writeFileSync(path.join(runDir, "verdict.json"), '{"verdict":"PASS"}');
    expect(scanArtifactsForSecrets(runDir, ["canary-secret-123"])).toEqual([]);
  });
});
