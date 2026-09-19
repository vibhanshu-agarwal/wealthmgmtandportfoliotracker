// @vitest-environment node
import { describe, expect, it } from "vitest";
import { LEG_IDS, loadLedgerContract, type LegId } from "../../../production-e2e/lib/contract";
import { LedgerSequenceError, LedgerWriter } from "../../../production-e2e/lib/ledger";
import {
  classifyError,
  decideMutationGate,
  FORBIDDEN_LEG_STATUSES,
  forbiddenStatusFailure,
  LegCheck,
  LegRunner,
  mutationSkippedReasonFor,
  type LegReport,
  type MutationGateInput,
} from "../../../production-e2e/lib/leg-runner";

const contract = loadLedgerContract();

function harness(options: { guard?: () => boolean } = {}) {
  const lines: string[] = [];
  const writer = new LedgerWriter({
    contract,
    sink: { append: (line) => void lines.push(line) },
    now: () => new Date("2026-09-18T00:00:00.000Z"),
  });
  const runner = new LegRunner({ writer, guard: options.guard });
  const events = () => lines.map((line) => JSON.parse(line) as Record<string, unknown>);
  const legEvents = () => events().filter((event) => event.event === "leg");
  const byLeg = (leg: LegId) => legEvents().filter((event) => event.leg === leg);
  return { runner, writer, lines, events, legEvents, byLeg };
}

const passing = (facts: LegReport["facts"] = {}): (() => Promise<LegReport>) => () =>
  Promise.resolve({ reason: null, http: [], facts });

const L7_PASS_FACTS = { pageWriteRequestsBeforeMutation: 0 };
/** L2 records first-attempt summary reads, so a passed L2 may carry further attempts after the first 200. */
const L2_PASS_FACTS = { freshnessState: "FRESH", countsValid: true, stripVisible: true };
const summaryRead = (status: number) => ({ method: "GET", path: "/api/portfolio/summary", status });

class NamedError extends Error {
  constructor(name: string, message: string) {
    super(message);
    this.name = name;
  }
}

describe("Step B 5b leg runner", () => {
  it("records a passing leg with its http entries and facts", async () => {
    const { runner, byLeg } = harness();
    const ok = await runner.run("L2", () =>
      Promise.resolve({ reason: null, http: [summaryRead(200)], facts: L2_PASS_FACTS }),
    );
    expect(ok).toBe(true);
    expect(runner.failed).toBe(false);
    expect(byLeg("L2")).toEqual([
      expect.objectContaining({
        status: "passed",
        reason: "OK",
        http: [summaryRead(200)],
        facts: L2_PASS_FACTS,
      }),
    ]);
  });

  it("records a reported failure and skips every later leg with SKIPPED_PRIOR_FAILURE", async () => {
    const { runner, byLeg } = harness();
    expect(await runner.run("L0", () => Promise.resolve({ reason: "ASSERTION_FAILED", http: [], facts: { headingPortfolio: false } }))).toBe(false);
    expect(runner.failed).toBe(true);
    let ran = false;
    expect(
      await runner.run("L1", () => {
        ran = true;
        return passing()();
      }),
    ).toBe(false);
    expect(ran).toBe(false);
    expect(byLeg("L0")[0]).toMatchObject({ status: "failed", reason: "ASSERTION_FAILED" });
    expect(byLeg("L1")[0]).toMatchObject({ status: "skipped", reason: "SKIPPED_PRIOR_FAILURE", facts: {} });
  });

  it("gives the skipped mutating legs the LEG_FAILED gate fact", async () => {
    const { runner, byLeg } = harness();
    await runner.run("L0", () => Promise.resolve({ reason: "TIMEOUT", http: [], facts: {} }));
    await runner.run("L8", passing());
    await runner.run("L9", passing());
    expect(byLeg("L8")[0]).toMatchObject({ status: "skipped", facts: { mutationSkippedReason: "LEG_FAILED" } });
    expect(byLeg("L9")[0]).toMatchObject({ status: "skipped", facts: { mutationSkippedReason: "LEG_FAILED" } });
  });

  it("turns a thrown error into a failed leg and never records its message", async () => {
    const { runner, lines, byLeg } = harness();
    const secret = "Bearer eyJhbGciOiJIUzI1NiJ9.PAGE-TEXT-AND-TOKEN.SIG at https://api.vibhanshu-ai-portfolio.dev/api/x?tickers=AAPL";
    await runner.run("L0", () => Promise.reject(new Error(secret)));
    expect(byLeg("L0")[0]).toMatchObject({ status: "failed", reason: "EXCEPTION", http: [], facts: {} });
    const written = lines.join("");
    expect(written).not.toContain("Bearer");
    expect(written).not.toContain("PAGE-TEXT");
    expect(written).not.toContain("tickers");
    expect(runner.failed).toBe(true);
  });

  it("records a Playwright TimeoutError as TIMEOUT", async () => {
    const { runner, byLeg } = harness();
    await runner.run("L3", () => Promise.reject(new NamedError("TimeoutError", "locator.click: Timeout 60000ms exceeded")));
    expect(byLeg("L3")[0]).toMatchObject({ status: "failed", reason: "TIMEOUT" });
  });

  it("keeps a leg whose report breaks the closed vocabulary, as failed EXCEPTION instead of losing it", async () => {
    const { runner, byLeg, lines } = harness();
    await runner.run("L0", () =>
      Promise.resolve({
        reason: null,
        http: [],
        facts: { finalPathIsPortfolio: "https://leaky.example/page text", headingPortfolio: true, redirectedToLogin: false, unauthorized401Seen: false },
      }),
    );
    expect(byLeg("L0")).toHaveLength(1);
    expect(byLeg("L0")[0]).toMatchObject({ status: "failed", reason: "EXCEPTION", http: [], facts: {} });
    expect(lines.join("")).not.toContain("leaky");
    expect(runner.failed).toBe(true);
    // ...and everything after it is skipped.
    await runner.run("L1", passing());
    expect(byLeg("L1")[0]).toMatchObject({ status: "skipped", reason: "SKIPPED_PRIOR_FAILURE" });
  });

  it("records an unknown fact key or a path outside the list the same way", async () => {
    const one = harness();
    await one.runner.run("L4", () => Promise.resolve({ reason: null, http: [], facts: { ticker: "AAPL" } }));
    expect(one.byLeg("L4")[0]).toMatchObject({ status: "failed", reason: "EXCEPTION" });
    const two = harness();
    await two.runner.run("L4", () =>
      Promise.resolve({ reason: "ASSERTION_FAILED", http: [{ method: "GET", path: "/api/assets?x=1", status: 200 }], facts: {} }),
    );
    expect(two.byLeg("L4")[0]).toMatchObject({ status: "failed", reason: "EXCEPTION", http: [] });
  });

  it("records a leg that reports a pass next to contradicting facts as failed EXCEPTION, keeping the leg but dropping its content", async () => {
    const one = harness();
    await one.runner.run("L4", () =>
      Promise.resolve({
        reason: null,
        http: [{ method: "GET", path: "/api/assets", status: 200 }],
        facts: {
          catalogStatus: 200,
          noIfNoneMatch: true,
          etagPresent: true,
          assetsNonEmpty: true,
          rowsRendered: true,
          catalogParity: false,
          activeCount: 159,
        },
      }),
    );
    expect(one.byLeg("L4")).toHaveLength(1);
    expect(one.byLeg("L4")[0]).toMatchObject({ status: "failed", reason: "EXCEPTION", http: [], facts: {} });
    expect(one.runner.failed).toBe(true);
    // ...and everything after it is skipped, exactly as for a vocabulary-breaking report.
    await one.runner.run("L5", passing());
    expect(one.byLeg("L5")[0]).toMatchObject({ status: "skipped", reason: "SKIPPED_PRIOR_FAILURE" });

    const two = harness();
    await two.runner.run("L7", passing({ pageWriteRequestsBeforeMutation: 1 }));
    expect(two.byLeg("L7")[0]).toMatchObject({ status: "failed", reason: "EXCEPTION", facts: {} });

    const three = harness();
    await three.runner.run("L2", passing({ freshnessState: "ABSENT", countsValid: true, stripVisible: true }));
    expect(three.byLeg("L2")[0]).toMatchObject({ status: "failed", reason: "EXCEPTION", facts: {} });
  });

  it("records a leg that reports a pass next to request evidence that contradicts it as failed EXCEPTION, dropping its content", async () => {
    const l8Facts = {
      putCount: 1,
      putStatus: 200,
      expectedVersionMatchesObserved: true,
      bodyMatchesExpectedDraft: true,
      versionAdvanced: true,
      savedStatusVisible: true,
      independentReadVersionMatches: true,
      independentReadHoldingsMatch: true,
      mutationSkippedReason: "NONE",
    };
    const put = (path: string, status: number) => ({ method: "PUT", path, status });
    // The four shapes the false-GO probes demonstrated: a 500 beside putStatus 200, no PUT beside putCount 1,
    // two PUTs beside putCount 1, and the other write's path.
    for (const http of [
      [put("/api/portfolio/holdings", 500)],
      [],
      [put("/api/portfolio/holdings", 200), put("/api/portfolio/holdings", 200)],
      [put("/api/portfolio/demo-reset", 200)],
    ]) {
      const { runner, byLeg, lines } = harness();
      expect(await runner.run("L8", () => Promise.resolve({ reason: null, http, facts: l8Facts }))).toBe(false);
      expect(byLeg("L8")).toHaveLength(1);
      expect(byLeg("L8")[0]).toMatchObject({ status: "failed", reason: "EXCEPTION", http: [], facts: {} });
      expect(runner.failed).toBe(true);
      expect(lines.join("")).not.toContain("demo-reset");
      // ...and everything after it is skipped.
      await runner.run("L9", passing());
      expect(byLeg("L9")[0]).toMatchObject({ status: "skipped", reason: "SKIPPED_PRIOR_FAILURE" });
    }
  });

  it("fails a leg that would pass when a run-wide safety guard has tripped", async () => {
    let tripped = false;
    const { runner, byLeg } = harness({ guard: () => tripped });
    expect(await runner.run("L7", passing(L7_PASS_FACTS))).toBe(true);
    tripped = true;
    expect(await runner.run("L2", passing())).toBe(false);
    expect(byLeg("L2")[0]).toMatchObject({ status: "failed", reason: "ASSERTION_FAILED" });
  });

  it.each([
    [409, "CONFLICT"],
    [429, "RATE_LIMITED"],
    [503, "HTTP_STATUS_NOT_200"],
    [504, "HTTP_STATUS_NOT_200"],
  ])("fails a leg that would pass when one of its own requests got a %i", async (status, reason) => {
    const { runner, byLeg } = harness();
    const ok = await runner.run("L2", () =>
      Promise.resolve({
        reason: null,
        http: [summaryRead(200), summaryRead(status)],
        facts: L2_PASS_FACTS,
      }),
    );
    expect(ok).toBe(false);
    expect(byLeg("L2")[0]).toMatchObject({ status: "failed", reason });
    // The offending entry is kept so the evidence shows what happened.
    expect(byLeg("L2")[0].http).toHaveLength(2);
  });

  it.each([200, 304, 401, 404, 500])("does not treat a %i as a forbidden status", async (status) => {
    const { runner, byLeg } = harness();
    const ok = await runner.run("L2", () =>
      Promise.resolve({
        reason: null,
        http: [summaryRead(200), summaryRead(status)],
        facts: L2_PASS_FACTS,
      }),
    );
    expect(ok).toBe(true);
    expect(byLeg("L2")[0]).toMatchObject({ status: "passed", reason: "OK" });
  });

  it("keeps the leg's own failure reason when it already failed", async () => {
    const { runner, byLeg } = harness();
    await runner.run("L7", () =>
      Promise.resolve({
        reason: "ASSERTION_FAILED",
        http: [{ method: "GET", path: "/api/assets", status: 429 }],
        facts: {},
      }),
    );
    expect(byLeg("L7")[0]).toMatchObject({ status: "failed", reason: "ASSERTION_FAILED" });
  });

  it("exposes the forbidden set the orchestrator also uses", () => {
    expect([...FORBIDDEN_LEG_STATUSES].sort()).toEqual([409, 429, 503, 504]);
    expect(forbiddenStatusFailure([])).toBeNull();
    expect(forbiddenStatusFailure([{ method: "GET", path: "/api/assets", status: 429 }])).toBe("RATE_LIMITED");
  });

  it("writes armed via the writer, once", () => {
    const { writer, events } = harness();
    writer.writeArmed();
    expect(events()).toEqual([expect.objectContaining({ event: "armed", seq: 1 })]);
    expect(() => writer.writeArmed()).toThrow(LedgerSequenceError);
  });

  it("propagates a duplicate final event instead of swallowing the bug", async () => {
    const { runner } = harness();
    await runner.run("L7", passing(L7_PASS_FACTS));
    await expect(runner.run("L7", passing(L7_PASS_FACTS))).rejects.toThrow(LedgerSequenceError);
  });
});

describe("Step B 5b leg runner: skips and finish", () => {
  it("skips a leg with an explicit reason and the matching gate fact, without marking a failure", () => {
    const { runner, byLeg } = harness();
    runner.skip("L8", "SKIPPED_VISITOR_PRESENT");
    runner.skip("L9", "SKIPPED_BASELINE_NOT_GOLDEN");
    expect(runner.failed).toBe(false);
    expect(byLeg("L8")[0]).toMatchObject({
      status: "skipped",
      reason: "SKIPPED_VISITOR_PRESENT",
      facts: { mutationSkippedReason: "VISITOR_PRESENT" },
    });
    expect(byLeg("L9")[0]).toMatchObject({
      status: "skipped",
      reason: "SKIPPED_BASELINE_NOT_GOLDEN",
      facts: { mutationSkippedReason: "BASELINE_NOT_GOLDEN" },
    });
  });

  it("maps each skip reason to its gate fact", () => {
    expect(mutationSkippedReasonFor("SKIPPED_PRIOR_FAILURE")).toBe("LEG_FAILED");
    expect(mutationSkippedReasonFor("SKIPPED_VISITOR_PRESENT")).toBe("VISITOR_PRESENT");
    expect(mutationSkippedReasonFor("SKIPPED_BASELINE_NOT_GOLDEN")).toBe("BASELINE_NOT_GOLDEN");
  });

  it("records a refused start as L0 failed and L1..L9 skipped", () => {
    const { runner, legEvents } = harness();
    runner.fail("L0", "ASSERTION_FAILED");
    runner.finish();
    const events = legEvents();
    expect(events.map((event) => event.leg)).toEqual([...LEG_IDS]);
    expect(events[0]).toMatchObject({ status: "failed", reason: "ASSERTION_FAILED" });
    for (const event of events.slice(1)) {
      expect(event).toMatchObject({ status: "skipped", reason: "SKIPPED_PRIOR_FAILURE" });
    }
  });

  it("finish() gives every leg exactly one final event, whatever ran", async () => {
    const { runner, legEvents } = harness();
    await runner.run("L0", passing({ finalPathIsPortfolio: true, headingPortfolio: true, redirectedToLogin: false, unauthorized401Seen: false }));
    await runner.run("L1", () => Promise.reject(new Error("boom")));
    runner.finish();
    runner.finish();
    const perLeg = LEG_IDS.map((leg) => legEvents().filter((event) => event.leg === leg).length);
    expect(perLeg).toEqual(Array(10).fill(1));
    expect(legEvents()[1]).toMatchObject({ leg: "L1", status: "failed", reason: "EXCEPTION" });
    expect(legEvents()[2]).toMatchObject({ leg: "L2", status: "skipped", reason: "SKIPPED_PRIOR_FAILURE" });
  });

  it("finish() records the first unreported leg as failed when nothing failed (a leg that never reports is a failure)", () => {
    const { runner, legEvents } = harness();
    runner.finish();
    expect(legEvents()[0]).toMatchObject({ leg: "L0", status: "failed", reason: "EXCEPTION" });
    expect(legEvents()[1]).toMatchObject({ leg: "L1", status: "skipped" });
    expect(legEvents()).toHaveLength(10);
  });

  it("finish() after a gate skip leaves the skipped legs alone", () => {
    const { runner, byLeg } = harness();
    for (const leg of LEG_IDS.slice(0, 8)) {
      runner.skip(leg, "SKIPPED_PRIOR_FAILURE");
    }
    runner.skip("L8", "SKIPPED_VISITOR_PRESENT");
    runner.skip("L9", "SKIPPED_VISITOR_PRESENT");
    runner.finish();
    expect(byLeg("L8")).toHaveLength(1);
    expect(byLeg("L8")[0]).toMatchObject({ reason: "SKIPPED_VISITOR_PRESENT" });
  });
});

describe("Step B 5b run-wide guard is evaluated once more in finish()", () => {
  /** Every leg recorded without any of them failing, so only the guard can make the run fail. */
  function allLegsRecordedWithoutFailure(runner: LegRunner) {
    for (const leg of LEG_IDS) runner.skip(leg, "SKIPPED_BASELINE_NOT_GOLDEN");
  }

  it("marks the run failed when the guard trips after the last leg was recorded", () => {
    let tripped = false;
    const { runner, lines } = harness({ guard: () => tripped });
    allLegsRecordedWithoutFailure(runner);
    expect(runner.failed).toBe(false);
    expect(runner.guardTripped).toBe(false);

    tripped = true;
    const linesBefore = lines.length;
    runner.finish();
    expect(runner.failed).toBe(true);
    expect(runner.guardTripped).toBe(true);
    // The events already written are not rewritten and nothing is appended: the exit code carries it.
    expect(lines).toHaveLength(linesBefore);
  });

  it("leaves the run alone when the guard never trips", () => {
    const { runner } = harness({ guard: () => false });
    allLegsRecordedWithoutFailure(runner);
    runner.finish();
    expect(runner.failed).toBe(false);
    expect(runner.guardTripped).toBe(false);
  });

  it("works without a guard", () => {
    const { runner } = harness();
    allLegsRecordedWithoutFailure(runner);
    runner.finish();
    expect(runner.failed).toBe(false);
  });

  it("keeps a guard that tripped while a leg ran, and never rewrites that leg as passed", async () => {
    let tripped = false;
    const { runner, byLeg } = harness({ guard: () => tripped });
    tripped = true;
    await runner.run("L7", passing(L7_PASS_FACTS));
    expect(byLeg("L7")[0]).toMatchObject({ status: "failed", reason: "ASSERTION_FAILED" });
    expect(runner.guardTripped).toBe(true);
    tripped = false;
    runner.finish();
    expect(runner.failed).toBe(true);
    expect(runner.guardTripped).toBe(true);
  });

  it("still gives every unreported leg its one final event when the guard has tripped", () => {
    const { runner, legEvents } = harness({ guard: () => true });
    runner.finish();
    expect(LEG_IDS.map((leg) => legEvents().filter((event) => event.leg === leg).length)).toEqual(Array(10).fill(1));
    expect(runner.failed).toBe(true);
    expect(runner.guardTripped).toBe(true);
  });
});

describe("Step B 5b mutation gate", () => {
  const open: MutationGateInput = {
    priorLegsPassed: true,
    baselineVersionSupplied: true,
    pageMatchesBaseline: true,
    anotherSessionActive: false,
    allowActivePresence: false,
  };

  it("runs the mutating legs only when everything holds", () => {
    expect(decideMutationGate(open)).toEqual({ run: true });
  });

  it("runs when another session is active and the owner allowed it", () => {
    expect(decideMutationGate({ ...open, anotherSessionActive: true, allowActivePresence: true })).toEqual({ run: true });
  });

  it("requires the absence of a visitor to be proven: an unknown presence blocks like a visitor", () => {
    expect(decideMutationGate({ ...open, anotherSessionActive: null })).toEqual({
      run: false,
      legReason: "SKIPPED_VISITOR_PRESENT",
    });
    expect(decideMutationGate({ ...open, anotherSessionActive: null, allowActivePresence: true })).toEqual({ run: true });
  });

  it.each([
    ["a leg failed", { priorLegsPassed: false }, "SKIPPED_PRIOR_FAILURE"],
    ["no baseline version was supplied", { baselineVersionSupplied: false }, "SKIPPED_BASELINE_NOT_GOLDEN"],
    ["the page does not match the baseline", { pageMatchesBaseline: false }, "SKIPPED_BASELINE_NOT_GOLDEN"],
    ["another session is active and not allowed", { anotherSessionActive: true }, "SKIPPED_VISITOR_PRESENT"],
  ] as const)("skips when %s", (_label, override, legReason) => {
    expect(decideMutationGate({ ...open, ...override })).toEqual({ run: false, legReason });
  });

  it("names the first failing condition when several fail", () => {
    expect(
      decideMutationGate({ ...open, priorLegsPassed: false, baselineVersionSupplied: false, anotherSessionActive: true }),
    ).toEqual({ run: false, legReason: "SKIPPED_PRIOR_FAILURE" });
    expect(decideMutationGate({ ...open, baselineVersionSupplied: false, anotherSessionActive: true })).toEqual({
      run: false,
      legReason: "SKIPPED_BASELINE_NOT_GOLDEN",
    });
  });
});

describe("Step B 5b error classification and checks", () => {
  it("classifies by error name and matcher result only", () => {
    expect(classifyError(new NamedError("TimeoutError", "x"))).toBe("TIMEOUT");
    expect(classifyError(Object.assign(new Error("expected"), { matcherResult: { pass: false } }))).toBe("ASSERTION_FAILED");
    expect(classifyError(new Error("Timeout 5000ms exceeded"))).toBe("EXCEPTION");
    expect(classifyError(new TypeError("x"))).toBe("EXCEPTION");
    expect(classifyError("a string")).toBe("EXCEPTION");
    expect(classifyError(undefined)).toBe("EXCEPTION");
  });

  it("lets the first failure decide the reason", () => {
    const check = new LegCheck();
    expect(check.failed).toBe(false);
    check.failIf(false, "TIMEOUT");
    check.fail("RATE_LIMITED");
    check.fail("CONFLICT");
    check.failIf(true, "TIMEOUT");
    expect(check.failed).toBe(true);
    expect(check.report([], { a: true })).toEqual({ reason: "RATE_LIMITED", http: [], facts: { a: true } });
    expect(new LegCheck().report([], {})).toEqual({ reason: null, http: [], facts: {} });
  });
});
