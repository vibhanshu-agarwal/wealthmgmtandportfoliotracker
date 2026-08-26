import { describe, expect, it } from "vitest";
import {
  buildSeedBody,
  formatG5Marker,
  isTerminalVersionConflict,
  isTransientSeedStatus,
  selectPortfolioVersion,
} from "../portfolio-seed-version";

const E2E_USER_ID = "00000000-0000-0000-0000-000000000e2e";
const OTHER_USER_ID = "11111111-1111-1111-1111-111111111111";

describe("selectPortfolioVersion", () => {
  it("returns a nonzero version unchanged", () => {
    const version = selectPortfolioVersion(
      [{ userId: E2E_USER_ID, version: 7 }],
      E2E_USER_ID,
    );
    expect(version).toBe(7);
  });

  it("ignores other users when selecting the fixed E2E portfolio", () => {
    const version = selectPortfolioVersion(
      [
        { userId: OTHER_USER_ID, version: 99 },
        { userId: E2E_USER_ID, version: 3 },
      ],
      E2E_USER_ID,
    );
    expect(version).toBe(3);
  });

  it("fails when zero portfolios match the fixed user", () => {
    expect(() =>
      selectPortfolioVersion([{ userId: OTHER_USER_ID, version: 1 }], E2E_USER_ID),
    ).toThrow(/exactly one portfolio/);
  });

  it("fails when duplicate portfolios match the fixed user", () => {
    expect(() =>
      selectPortfolioVersion(
        [
          { userId: E2E_USER_ID, version: 1 },
          { userId: E2E_USER_ID, version: 2 },
        ],
        E2E_USER_ID,
      ),
    ).toThrow(/exactly one portfolio/);
  });

  it.each([
    ["absent", { userId: E2E_USER_ID }],
    ["null", { userId: E2E_USER_ID, version: null }],
    ["string", { userId: E2E_USER_ID, version: "1" }],
    ["boolean", { userId: E2E_USER_ID, version: true }],
    ["fractional", { userId: E2E_USER_ID, version: 1.5 }],
    ["negative", { userId: E2E_USER_ID, version: -1 }],
    ["NaN", { userId: E2E_USER_ID, version: Number.NaN }],
    ["unsafe integer", { userId: E2E_USER_ID, version: Number.MAX_SAFE_INTEGER + 1 }],
  ])("fails when version is %s", (_label, row) => {
    expect(() => selectPortfolioVersion([row], E2E_USER_ID)).toThrow(
      /non-negative safe integer/,
    );
  });

  it("accepts version 0", () => {
    expect(
      selectPortfolioVersion([{ userId: E2E_USER_ID, version: 0 }], E2E_USER_ID),
    ).toBe(0);
  });
});

describe("seed status classification", () => {
  it("treats 409 as terminal and non-transient", () => {
    expect(isTerminalVersionConflict(409)).toBe(true);
    expect(isTransientSeedStatus(409)).toBe(false);
  });

  it.each([429, 500, 502, 503, 504])(
    "treats HTTP %i as transient transport/cold-start",
    (status) => {
      expect(isTransientSeedStatus(status)).toBe(true);
      expect(isTerminalVersionConflict(status)).toBe(false);
    },
  );
});

describe("G5 marker and seed body", () => {
  it("formats sanitized markers without secrets", () => {
    expect(formatG5Marker("synthetic-shell", 4)).toBe(
      "[b1-g5][synthetic-shell] expectedVersion=4",
    );
    expect(formatG5Marker("global-setup", 0)).toBe(
      "[b1-g5][global-setup] expectedVersion=0",
    );
    expect(formatG5Marker("azure-api-smoke", 12)).toBe(
      "[b1-g5][azure-api-smoke] expectedVersion=12",
    );
  });

  it("builds an authoritative expectedVersion body", () => {
    expect(buildSeedBody(5)).toEqual({ expectedVersion: 5 });
  });
});
