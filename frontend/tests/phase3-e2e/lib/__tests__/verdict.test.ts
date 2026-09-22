import { describe, expect, it } from "vitest";
import { computeVerdict, EXPECTED_SCENARIOS, scenarioIdOf, type CollectedScenario } from "../verdict";

function allPassed(): CollectedScenario[] {
  return EXPECTED_SCENARIOS.map((id) => ({ id, outcome: "passed" as const }));
}

describe("computeVerdict", () => {
  it("passes only when every expected scenario ran and passed", () => {
    expect(computeVerdict(allPassed(), { filtered: false })).toMatchObject({
      verdict: "PASS",
      failed: [],
      unrun: [],
    });
  });

  it("is incomplete when a scenario was skipped", () => {
    const collected = allPassed().map((s) => (s.id === "S12" ? { ...s, outcome: "skipped" as const } : s));
    expect(computeVerdict(collected, { filtered: false })).toMatchObject({ verdict: "INCOMPLETE", unrun: ["S12"] });
  });

  it("is incomplete when a scenario was never collected", () => {
    const collected = allPassed().filter((s) => s.id !== "S07");
    expect(computeVerdict(collected, { filtered: false })).toMatchObject({ verdict: "INCOMPLETE", unrun: ["S07"] });
  });

  it("is incomplete when the run was filtered, even if everything collected passed", () => {
    expect(computeVerdict(allPassed(), { filtered: true })).toMatchObject({ verdict: "INCOMPLETE" });
  });

  it("is incomplete when a scenario was interrupted", () => {
    const collected = allPassed().map((s) => (s.id === "S99" ? { ...s, outcome: "interrupted" as const } : s));
    expect(computeVerdict(collected, { filtered: false })).toMatchObject({ verdict: "INCOMPLETE", unrun: ["S99"] });
  });

  it("fails when any scenario failed or timed out, and still lists what did not run", () => {
    const collected = allPassed()
      .map((s) => (s.id === "S05" ? { ...s, outcome: "failed" as const } : s))
      .map((s) => (s.id === "S06" ? { ...s, outcome: "timedOut" as const } : s))
      .filter((s) => s.id !== "S13");
    expect(computeVerdict(collected, { filtered: false })).toMatchObject({
      verdict: "FAIL",
      failed: ["S05", "S06"],
      unrun: ["S13"],
    });
  });

  it("reports PASS_WITH_EXPECTED_DEFECTS when everything passed but an expected defect was observed", () => {
    expect(
      computeVerdict(allPassed(), { filtered: false, expectedDefects: ["non-demo-reset-control-visible"] }),
    ).toMatchObject({ verdict: "PASS_WITH_EXPECTED_DEFECTS", expectedDefects: ["non-demo-reset-control-visible"] });
  });

  it("never lets an expected defect soften a failure or an incomplete run", () => {
    const failed = allPassed().map((s) => (s.id === "S05" ? { ...s, outcome: "failed" as const } : s));
    expect(computeVerdict(failed, { filtered: false, expectedDefects: ["x"] })).toMatchObject({ verdict: "FAIL" });
    const skipped = allPassed().filter((s) => s.id !== "S05");
    expect(computeVerdict(skipped, { filtered: false, expectedDefects: ["x"] })).toMatchObject({ verdict: "INCOMPLETE" });
  });

  it("is incomplete when an unexpected or duplicate scenario id appears", () => {
    expect(computeVerdict([...allPassed(), { id: "S42", outcome: "passed" }], { filtered: false })).toMatchObject({
      verdict: "INCOMPLETE",
      unexpected: ["S42"],
    });
    expect(computeVerdict([...allPassed(), { id: "S01", outcome: "passed" }], { filtered: false })).toMatchObject({
      verdict: "INCOMPLETE",
      unexpected: ["S01"],
    });
  });
});

describe("scenarioIdOf", () => {
  it("reads the leading scenario id from a test title", () => {
    expect(scenarioIdOf("S05 empty portfolio → add first holdings")).toBe("S05");
    expect(scenarioIdOf("S99 final state")).toBe("S99");
  });

  it("returns null for titles without a scenario id", () => {
    expect(scenarioIdOf("helper")).toBeNull();
    expect(scenarioIdOf("s05 lowercase")).toBeNull();
    expect(scenarioIdOf("S5 short")).toBeNull();
  });
});
