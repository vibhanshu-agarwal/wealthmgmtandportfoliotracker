/**
 * Verdict rule: PASS only when every expected scenario was collected, ran and passed.
 * Anything skipped, filtered, uncollected or interrupted makes the run INCOMPLETE; any
 * failure makes it FAIL. The expected list is a constant compared with what ran.
 */
export const EXPECTED_SCENARIOS = [
  "S00", "S01", "S02", "S03", "S04", "S05", "S06", "S07",
  "S08", "S09", "S10", "S11", "S12", "S13", "S99",
] as const;

/**
 * The only product defects a scenario may record as expected instead of failing. Each has
 * a pending owner decision (design section 10: D5, D9, D10). Any other id makes the run FAIL,
 * so a failing check cannot be quietly converted into an "expected defect".
 */
export const KNOWN_EXPECTED_DEFECTS = [
  "non-demo-reset-control-visible",
  "partial-valuation-not-presented",
  "analytics-cache-stale-after-holdings-write",
] as const;

export type ScenarioOutcome = "passed" | "failed" | "timedOut" | "skipped" | "interrupted";

export interface CollectedScenario {
  readonly id: string;
  readonly outcome: ScenarioOutcome;
}

export interface Verdict {
  readonly verdict: "PASS" | "PASS_WITH_EXPECTED_DEFECTS" | "FAIL" | "INCOMPLETE";
  readonly failed: string[];
  readonly unrun: string[];
  readonly unexpected: string[];
  readonly filtered: boolean;
  readonly expectedDefects: string[];
  readonly unknownDefects: string[];
}

export function scenarioIdOf(title: string): string | null {
  const match = /^(S\d{2})\b/.exec(title);
  return match ? match[1] : null;
}

/**
 * An expected defect is a known product defect that a scenario observed and recorded
 * without failing (for example S13 until owner decision D5). It can only qualify a run
 * that would otherwise PASS; it never softens FAIL or INCOMPLETE.
 */
export function computeVerdict(
  collected: readonly CollectedScenario[],
  options: { filtered: boolean; expectedDefects?: readonly string[] },
): Verdict {
  const known = new Set<string>(KNOWN_EXPECTED_DEFECTS);
  const recorded = [...(options.expectedDefects ?? [])];
  const expectedDefects = recorded.filter((id) => known.has(id));
  const unknownDefects = recorded.filter((id) => !known.has(id));
  const expected = new Set<string>(EXPECTED_SCENARIOS);
  const seen = new Map<string, ScenarioOutcome>();
  const unexpected: string[] = [];

  for (const scenario of collected) {
    if (!expected.has(scenario.id) || seen.has(scenario.id)) {
      unexpected.push(scenario.id);
      continue;
    }
    seen.set(scenario.id, scenario.outcome);
  }

  const failed: string[] = [];
  const unrun: string[] = [];
  for (const id of EXPECTED_SCENARIOS) {
    const outcome = seen.get(id);
    if (outcome === "failed" || outcome === "timedOut") failed.push(id);
    else if (outcome !== "passed") unrun.push(id);
  }

  let verdict: Verdict["verdict"] = expectedDefects.length > 0 ? "PASS_WITH_EXPECTED_DEFECTS" : "PASS";
  if (failed.length > 0 || unknownDefects.length > 0) verdict = "FAIL";
  else if (unrun.length > 0 || unexpected.length > 0 || options.filtered) verdict = "INCOMPLETE";

  return { verdict, failed, unrun, unexpected, filtered: options.filtered, expectedDefects, unknownDefects };
}
