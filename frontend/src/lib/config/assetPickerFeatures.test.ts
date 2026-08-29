/**
 * B2 Task 1.1 — build-time feature flags for the Asset Picker's user-facing entry points.
 *
 * These are NOT runtime configuration: `next.config.ts` sets `output: "export"`, so
 * `NEXT_PUBLIC_*` values are inlined at build time. "Enabling a flag" is always a new
 * build-and-deploy (Wave 10 owns that mechanics), never a config toggle.
 *
 * The parse is an exact string match, never truthiness — a truthiness check would treat
 * the rollback value `"false"` as a non-empty, therefore truthy, string and defeat the
 * rollback mechanism the moment it was used.
 */
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  isAssetPickerEnabled,
  isDemoResetControlEnabled,
  parseFeatureFlag,
} from "./assetPickerFeatures";

afterEach(() => {
  vi.unstubAllEnvs();
});

describe("parseFeatureFlag", () => {
  it("enables only on the exact normalized string \"true\"", () => {
    expect(parseFeatureFlag("true")).toBe(true);
  });

  it.each([
    ["TRUE", true],
    ["True", true],
    ["  true  ", true],
  ])("normalizes %j by trimming and lowercasing", (raw, expected) => {
    expect(parseFeatureFlag(raw)).toBe(expected);
  });

  it.each([
    [undefined],
    [""],
    ["false"],
    ["0"],
    ["1"],
    ["yes"],
    ["on"],
    ["truthy"],
    ["  "],
  ])("treats %j as disabled", (raw) => {
    expect(parseFeatureFlag(raw)).toBe(false);
  });

  it("is not a truthiness check — the rollback value \"false\" disables", () => {
    // A `if (flagValue)` implementation would return true here, since "false" is a
    // non-empty string. That is the exact defect this parse exists to prevent.
    expect(parseFeatureFlag("false")).toBe(false);
  });
});

describe("isAssetPickerEnabled", () => {
  it.each([
    [undefined, false],
    ["", false],
    ["false", false],
    ["0", false],
    ["true", true],
  ])("NEXT_PUBLIC_ENABLE_ASSET_PICKER=%j → %s", (raw, expected) => {
    vi.stubEnv("NEXT_PUBLIC_ENABLE_ASSET_PICKER", raw);
    expect(isAssetPickerEnabled()).toBe(expected);
  });

  it("defaults to disabled by omission", () => {
    vi.stubEnv("NEXT_PUBLIC_ENABLE_ASSET_PICKER", undefined);
    expect(isAssetPickerEnabled()).toBe(false);
  });

  it("is independent of the demo-reset control flag", () => {
    vi.stubEnv("NEXT_PUBLIC_ENABLE_ASSET_PICKER", undefined);
    vi.stubEnv("NEXT_PUBLIC_ENABLE_DEMO_RESET_CONTROL", "true");
    expect(isAssetPickerEnabled()).toBe(false);
  });
});

describe("isDemoResetControlEnabled", () => {
  it.each([
    [undefined, false],
    ["", false],
    ["false", false],
    ["0", false],
    ["true", true],
  ])("NEXT_PUBLIC_ENABLE_DEMO_RESET_CONTROL=%j → %s", (raw, expected) => {
    vi.stubEnv("NEXT_PUBLIC_ENABLE_DEMO_RESET_CONTROL", raw);
    expect(isDemoResetControlEnabled()).toBe(expected);
  });

  it("defaults to disabled by omission", () => {
    vi.stubEnv("NEXT_PUBLIC_ENABLE_DEMO_RESET_CONTROL", undefined);
    expect(isDemoResetControlEnabled()).toBe(false);
  });

  it("is independent of the asset picker flag", () => {
    vi.stubEnv("NEXT_PUBLIC_ENABLE_DEMO_RESET_CONTROL", undefined);
    vi.stubEnv("NEXT_PUBLIC_ENABLE_ASSET_PICKER", "true");
    expect(isDemoResetControlEnabled()).toBe(false);
  });
});
