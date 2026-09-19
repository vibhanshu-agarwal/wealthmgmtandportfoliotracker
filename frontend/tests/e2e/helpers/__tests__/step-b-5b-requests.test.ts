// @vitest-environment node
import { describe, expect, it } from "vitest";
import { LEDGER_PATHS } from "../../../production-e2e/lib/contract";
import {
  classifyRequest,
  isWriteMethod,
  pathnameOf,
  templateLedgerPath,
  urlIsOnOriginAtPath,
} from "../../../production-e2e/lib/requests";

const FRONTEND = "https://vibhanshu-ai-portfolio.dev";
const API = "https://api.vibhanshu-ai-portfolio.dev";
const ORIGINS = { frontend: FRONTEND, api: API };
const PATHS: readonly string[] = LEDGER_PATHS;

describe("Step B 5b path templating", () => {
  it.each(LEDGER_PATHS)("keeps the contract path %s and drops the query string", (path) => {
    expect(templateLedgerPath(`${API}${path}`, API, PATHS)).toBe(path);
    expect(templateLedgerPath(`${API}${path}?userId=00000000-0000-0000-0000-0000000d3110&x=1#frag`, API, PATHS)).toBe(
      path,
    );
  });

  it("never lets a query value survive into the template", () => {
    const templated = templateLedgerPath(`${API}/api/market/prices?tickers=AAPL%2CBTC-USD%2CSECRETTICKER`, API, PATHS);
    expect(templated).toBe("/api/market/prices");
    expect(JSON.stringify(templated)).not.toMatch(/AAPL|BTC|SECRETTICKER|tickers/);
  });

  it.each([
    ["an unknown api path", `${API}/api/auth/login`],
    ["the insights path the layout ticker calls", `${API}/api/insights/market-summary`],
    ["a child of a known path", `${API}/api/portfolio/summary/extra`],
    ["a trailing slash", `${API}/api/portfolio/`],
    ["a different case", `${API}/API/portfolio`],
    ["a percent-encoded separator", `${API}/api/portfolio%2Fsummary`],
    ["the root", `${API}/`],
    ["a path on the frontend origin", `${FRONTEND}/api/portfolio`],
    ["a foreign origin", "https://evil.example/api/portfolio"],
    ["a userinfo trick", `${API}@evil.example/api/portfolio`],
    ["a data url", "data:text/plain,/api/portfolio"],
    ["garbage", "not a url"],
    ["an empty string", ""],
  ])("rejects %s", (_label, url) => {
    expect(templateLedgerPath(url, API, PATHS)).toBeNull();
  });

  it("only accepts paths from the list it is given", () => {
    expect(templateLedgerPath(`${API}/api/assets`, API, ["/api/portfolio"])).toBeNull();
    expect(templateLedgerPath(`${API}/api/portfolio`, API, [])).toBeNull();
  });
});

describe("Step B 5b request classification", () => {
  it("classifies by exact origin", () => {
    expect(classifyRequest("GET", `${API}/api/assets`, ORIGINS, PATHS)).toEqual({
      kind: "api",
      method: "GET",
      path: "/api/assets",
      isWrite: false,
    });
    expect(classifyRequest("GET", `${FRONTEND}/_next/static/x.js`, ORIGINS, PATHS)).toEqual({
      kind: "frontend",
      method: "GET",
      path: null,
      isWrite: false,
    });
    expect(classifyRequest("GET", "https://fonts.example/f.woff2", ORIGINS, PATHS).kind).toBe("other");
    expect(classifyRequest("GET", `${API}:8443/api/assets`, ORIGINS, PATHS).kind).toBe("other");
    expect(classifyRequest("GET", "http://api.vibhanshu-ai-portfolio.dev/api/assets", ORIGINS, PATHS).kind).toBe(
      "other",
    );
    expect(classifyRequest("GET", "data:text/plain,x", ORIGINS, PATHS).kind).toBe("other");
  });

  it("gives an api request outside the path list no template but keeps it classified as api", () => {
    expect(classifyRequest("GET", `${API}/api/portfolio/analytics`, ORIGINS, PATHS)).toEqual({
      kind: "api",
      method: "GET",
      path: null,
      isWrite: false,
    });
  });

  it("normalizes the method and flags writes", () => {
    expect(classifyRequest("put", `${API}/api/portfolio/holdings`, ORIGINS, PATHS)).toEqual({
      kind: "api",
      method: "PUT",
      path: "/api/portfolio/holdings",
      isWrite: true,
    });
  });
});

describe("Step B 5b write detection", () => {
  it.each(["GET", "get", "OPTIONS", "options"])("treats %s as not a write", (method) => {
    expect(isWriteMethod(method)).toBe(false);
  });

  it.each(["PUT", "put", "POST", "PATCH", "DELETE", "HEAD", "", "TRACE"])("treats %j as a write", (method) => {
    expect(isWriteMethod(method)).toBe(true);
  });
});

describe("Step B 5b origin and path check (L0 final location)", () => {
  it("accepts only the frontend origin at exactly /portfolio, ignoring query and fragment", () => {
    expect(urlIsOnOriginAtPath(`${FRONTEND}/portfolio`, FRONTEND, "/portfolio")).toBe(true);
    expect(urlIsOnOriginAtPath(`${FRONTEND}/portfolio?x=1#y`, FRONTEND, "/portfolio")).toBe(true);
  });

  it("refuses /portfolio on a foreign origin, which a pathname-only check would accept", () => {
    expect(pathnameOf("https://evil.example/portfolio")).toBe("/portfolio");
    expect(urlIsOnOriginAtPath("https://evil.example/portfolio", FRONTEND, "/portfolio")).toBe(false);
    expect(urlIsOnOriginAtPath(`${API}/portfolio`, FRONTEND, "/portfolio")).toBe(false);
  });

  it.each([
    ["a different scheme", "http://vibhanshu-ai-portfolio.dev/portfolio"],
    ["a different port", "https://vibhanshu-ai-portfolio.dev:8443/portfolio"],
    ["a look-alike host", "https://vibhanshu-ai-portfolio.dev.evil.example/portfolio"],
    ["a userinfo trick", `${FRONTEND}@evil.example/portfolio`],
    ["a login redirect", `${FRONTEND}/login?next=%2Fportfolio`],
    ["a trailing slash", `${FRONTEND}/portfolio/`],
    ["a different case", `${FRONTEND}/Portfolio`],
    ["a child path", `${FRONTEND}/portfolio/edit`],
    ["about:blank", "about:blank"],
    ["a data url", "data:text/plain,/portfolio"],
    ["garbage", "not a url"],
    ["an empty string", ""],
  ])("refuses %s", (_label, url) => {
    expect(urlIsOnOriginAtPath(url, FRONTEND, "/portfolio")).toBe(false);
  });

  it("returns false rather than throwing when the URL cannot be parsed", () => {
    expect(() => urlIsOnOriginAtPath("http://[bad", FRONTEND, "/portfolio")).not.toThrow();
    expect(urlIsOnOriginAtPath("http://[bad", FRONTEND, "/portfolio")).toBe(false);
  });
});

describe("Step B 5b pathnameOf", () => {
  it("returns the pathname without query or fragment, and an empty string for non-URLs", () => {
    expect(pathnameOf(`${FRONTEND}/portfolio?x=1#y`)).toBe("/portfolio");
    expect(pathnameOf("about:blank")).toBe("blank");
    expect(pathnameOf("not a url")).toBe("");
  });
});
