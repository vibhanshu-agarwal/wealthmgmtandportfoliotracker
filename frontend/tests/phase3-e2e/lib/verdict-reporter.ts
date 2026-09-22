/**
 * Writes <runDir>/verdict.json from what Playwright actually collected and ran.
 * A run is "filtered" when any grep, grep-invert, project, shard or last-failed filter
 * is active, or when fewer tests were collected than the suite declares.
 */
import { mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import type { FullConfig, Reporter, Suite, TestCase, TestResult } from "@playwright/test/reporter";
import { computeVerdict, EXPECTED_SCENARIOS, scenarioIdOf, type CollectedScenario, type ScenarioOutcome } from "./verdict";

function isDefaultGrep(grep: RegExp | RegExp[]): boolean {
  const patterns = Array.isArray(grep) ? grep : [grep];
  return patterns.length === 1 && patterns[0].source === ".*" && patterns[0].flags === "";
}

export default class VerdictReporter implements Reporter {
  private filtered = false;
  private readonly outcomes = new Map<TestCase, ScenarioOutcome>();
  private collected: TestCase[] = [];

  onBegin(config: FullConfig, suite: Suite): void {
    this.collected = suite.allTests();
    const project = config.projects[0];
    this.filtered =
      !isDefaultGrep(config.grep) ||
      config.grepInvert !== null ||
      (project !== undefined && (!isDefaultGrep(project.grep) || project.grepInvert !== null)) ||
      config.shard !== null ||
      this.collected.length !== EXPECTED_SCENARIOS.length;
  }

  private readonly expectedDefects: string[] = [];

  onTestEnd(test: TestCase, result: TestResult): void {
    this.outcomes.set(test, result.status as ScenarioOutcome);
    for (const annotation of result.annotations) {
      const defect = annotation.description;
      if (annotation.type === "expected-defect" && defect && !this.expectedDefects.includes(defect)) this.expectedDefects.push(defect);
    }
  }

  onEnd(): void {
    const runDir = process.env.P3_RUN_DIR;
    const collected: CollectedScenario[] = this.collected.map((test) => ({
      id: scenarioIdOf(test.title) ?? `untitled:${test.title.slice(0, 40)}`,
      outcome: this.outcomes.get(test) ?? "skipped",
    }));
    const verdict = computeVerdict(collected, { filtered: this.filtered, expectedDefects: this.expectedDefects });
    const record = {
      ...verdict,
      expected: [...EXPECTED_SCENARIOS],
      outcomes: collected,
      writtenAt: new Date().toISOString(),
    };
    if (runDir) {
      mkdirSync(runDir, { recursive: true });
      writeFileSync(path.join(runDir, "verdict.json"), `${JSON.stringify(record, null, 2)}\n`);
    }
    process.stdout.write(
      `\nPHASE3 VERDICT: ${verdict.verdict}  failed=[${verdict.failed.join(",")}] unrun=[${verdict.unrun.join(",")}] unexpected=[${verdict.unexpected.join(",")}] filtered=${verdict.filtered} expectedDefects=[${verdict.expectedDefects.join(",")}]\n`,
    );
  }
}
