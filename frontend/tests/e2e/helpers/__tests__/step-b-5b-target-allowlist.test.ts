// @vitest-environment node
import { describe, expect, it } from "vitest";
import {
  checkTargets,
  isAllowlistedOrigin,
  STEP_B_5B_ALLOWLIST,
  type TargetAllowlist,
} from "../../../production-e2e/lib/target-allowlist";

const FRONTEND = "https://vibhanshu-ai-portfolio.dev";
const API = "https://api.vibhanshu-ai-portfolio.dev";

describe("Step B 5b target allowlist", () => {
  it("pins exactly the two production origins and freezes them", () => {
    expect(STEP_B_5B_ALLOWLIST).toEqual({ frontend: FRONTEND, api: API });
    expect(Object.isFrozen(STEP_B_5B_ALLOWLIST)).toBe(true);
    expect(FRONTEND.startsWith("https://")).toBe(true);
    expect(API.startsWith("https://")).toBe(true);
  });

  it("accepts the two allowlisted origins", () => {
    expect(checkTargets(FRONTEND, API)).toEqual({ ok: true, frontend: FRONTEND, api: API });
  });

  it.each([
    ["http scheme", "http://vibhanshu-ai-portfolio.dev"],
    ["other host", "https://evil.example"],
    ["subdomain of the frontend host", "https://www.vibhanshu-ai-portfolio.dev"],
    ["lookalike suffix", "https://vibhanshu-ai-portfolio.dev.evil.example"],
    ["lookalike prefix", "https://evilvibhanshu-ai-portfolio.dev"],
    ["non-default port", "https://vibhanshu-ai-portfolio.dev:8443"],
    ["explicit default port", "https://vibhanshu-ai-portfolio.dev:443"],
    ["trailing slash", "https://vibhanshu-ai-portfolio.dev/"],
    ["trailing path", "https://vibhanshu-ai-portfolio.dev/portfolio"],
    ["query", "https://vibhanshu-ai-portfolio.dev?x=1"],
    ["fragment", "https://vibhanshu-ai-portfolio.dev#x"],
    ["userinfo trick (allowlisted host as userinfo)", "https://vibhanshu-ai-portfolio.dev@evil.example"],
    ["userinfo in front of the real host", "https://evil.example@vibhanshu-ai-portfolio.dev"],
    ["backslash userinfo trick", "https://vibhanshu-ai-portfolio.dev\\@evil.example"],
    ["upper-case host", "https://VIBHANSHU-AI-PORTFOLIO.DEV"],
    ["upper-case scheme", "HTTPS://vibhanshu-ai-portfolio.dev"],
    ["leading whitespace", " https://vibhanshu-ai-portfolio.dev"],
    ["trailing whitespace", "https://vibhanshu-ai-portfolio.dev "],
    ["cyrillic look-alike letter", "https://vibhanshu-ai-portfolio.dev".replace("a", "а")],
    ["localhost", "http://localhost:3000"],
  ])("rejects the frontend candidate: %s", (_label, candidate) => {
    expect(checkTargets(candidate, API)).toEqual({ ok: false, problems: ["FRONTEND_NOT_ALLOWLISTED"] });
  });

  it.each([
    ["http scheme", "http://api.vibhanshu-ai-portfolio.dev"],
    ["the frontend origin", FRONTEND],
    ["other host", "https://api.evil.example"],
    ["port", "https://api.vibhanshu-ai-portfolio.dev:8080"],
    ["trailing path", "https://api.vibhanshu-ai-portfolio.dev/api"],
    ["userinfo trick", "https://api.vibhanshu-ai-portfolio.dev@evil.example"],
    ["local gateway", "http://localhost:8080"],
  ])("rejects the API candidate: %s", (_label, candidate) => {
    expect(checkTargets(FRONTEND, candidate)).toEqual({ ok: false, problems: ["API_NOT_ALLOWLISTED"] });
  });

  it("rejects swapped origins", () => {
    expect(checkTargets(API, FRONTEND)).toEqual({
      ok: false,
      problems: ["FRONTEND_NOT_ALLOWLISTED", "API_NOT_ALLOWLISTED"],
    });
  });

  it("reports missing values by fixed code without echoing anything", () => {
    expect(checkTargets(undefined, "")).toEqual({ ok: false, problems: ["FRONTEND_MISSING", "API_MISSING"] });
    const bad = checkTargets("https://evil.example/secret-path", API);
    expect(JSON.stringify(bad)).not.toContain("evil");
    expect(JSON.stringify(bad)).not.toContain("secret-path");
  });

  it("rejects non-string candidates", () => {
    expect(isAllowlistedOrigin(undefined, FRONTEND)).toBe(false);
    expect(isAllowlistedOrigin(null, FRONTEND)).toBe(false);
    expect(isAllowlistedOrigin(42, FRONTEND)).toBe(false);
    expect(isAllowlistedOrigin({ toString: () => FRONTEND }, FRONTEND)).toBe(false);
  });

  it("takes an injected allowlist as a parameter and never consults the environment", () => {
    const injected: TargetAllowlist = { frontend: "https://front.test", api: "https://api.test" };
    expect(checkTargets("https://front.test", "https://api.test", injected)).toEqual({
      ok: true,
      frontend: "https://front.test",
      api: "https://api.test",
    });
    // The production origins are NOT accepted under an injected allowlist, and vice versa.
    expect(checkTargets(FRONTEND, API, injected).ok).toBe(false);
    expect(checkTargets("https://front.test", "https://api.test").ok).toBe(false);

    const saved = process.env.STEP_B_5B_ALLOW_ANY_TARGET;
    process.env.STEP_B_5B_ALLOW_ANY_TARGET = "1";
    try {
      expect(checkTargets("https://front.test", "https://api.test").ok).toBe(false);
    } finally {
      if (saved === undefined) delete process.env.STEP_B_5B_ALLOW_ANY_TARGET;
      else process.env.STEP_B_5B_ALLOW_ANY_TARGET = saved;
    }
  });
});
