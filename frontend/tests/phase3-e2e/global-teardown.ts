/**
 * Restoration safety net. S99 restores the certification accounts and writes
 * restoration.json; if that did not happen (S99 failed, was skipped or the run was
 * interrupted), this teardown attempts the same restoration and records the outcome
 * in restoration-teardown.json as restored | not_needed | unconfirmed.
 */
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { ApiClient } from "./lib/api";
import { fileStore, paceAuthRequest } from "./lib/auth-pacer";
import { resolveRunConfig } from "./lib/config";
import { BASELINES, ensureHoldings } from "./lib/roles";

export default async function globalTeardown(): Promise<void> {
  const runDir = process.env.P3_RUN_DIR;
  if (!runDir) return;
  const confirmed = path.join(runDir, "restoration.json");
  if (existsSync(confirmed) && (JSON.parse(readFileSync(confirmed, "utf8")) as { status?: string }).status === "confirmed") {
    return;
  }
  const resolved = resolveRunConfig(process.env, {
    repoRoot: path.resolve(__dirname, "../../.."),
    now: new Date(),
    randomHex: () => "0000",
  });
  const perRole: Record<string, string> = {};
  let status: "restored" | "not_needed" | "unconfirmed" = "not_needed";
  if (!resolved.ok) {
    status = "unconfirmed";
  } else {
    const run = resolved.config;
    const pacer = fileStore(path.join(runDir, "auth-pacer.json"));
    const api = new ApiClient(run.api, async () => {
      await paceAuthRequest(pacer, run.authMinIntervalMs);
    });
    for (const role of ["CERT_A", "CERT_B"] as const) {
      try {
        const session = await api.login(role === "CERT_A" ? run.certA : run.certB);
        if (!session) {
          perRole[role] = "unconfirmed";
          status = "unconfirmed";
          continue;
        }
        const result = await ensureHoldings(api, session, BASELINES[role]);
        perRole[role] = result.outcome === "written" ? "restored" : "not_needed";
        if (result.outcome === "written" && status === "not_needed") status = "restored";
      } catch {
        perRole[role] = "unconfirmed";
        status = "unconfirmed";
      }
    }
  }
  writeFileSync(path.join(runDir, "restoration-teardown.json"), `${JSON.stringify({ status, perRole, at: new Date().toISOString() }, null, 2)}\n`);
  process.stdout.write(`\nPHASE3 RESTORATION (teardown safety net): ${status} ${JSON.stringify(perRole)}\n`);
}
