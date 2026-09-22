import { describe, expect, it } from "vitest";
import { classifyConsoleError, classifyHttpFailure, resourceFailureStatus } from "../classify";

describe("resourceFailureStatus", () => {
  it.each([
    ["Failed to load resource: the server responded with a status of 409 ()", 409],
    ["Failed to load resource: the server responded with a status of 401 (Unauthorized)", 401],
    ["Failed to load resource: the server responded with a status of 404 (Not Found)", 404],
  ])("reads the status from %j", (text, status) => {
    expect(resourceFailureStatus(text)).toBe(status);
  });

  it("returns null for other console text", () => {
    expect(resourceFailureStatus("Minified React error #418")).toBeNull();
    expect(resourceFailureStatus("Failed to load resource: net::ERR_FAILED")).toBeNull();
  });
});

const FRONTEND = "http://localhost:3000";

describe("classifyHttpFailure", () => {
  it.each([
    "/portfolio/__next.!KGRhc2hib2FyZCk.portfolio.txt?_rsc=abc12",
    "/portfolio/__next.!KGRhc2hib2FyZCk.portfolio.__PAGE__.txt?_rsc=abc12",
    "/market-data/__next.!KGRhc2hib2FyZCk.market-data.txt?_rsc=zz",
    "/ai-insights/__next.!KGRhc2hib2FyZCk.ai-insights.__PAGE__.txt?_rsc=1",
  ])("treats the dashboard segment-prefetch 404 %s as known noise", (pathAndQuery) => {
    expect(classifyHttpFailure(`${FRONTEND}${pathAndQuery}`, 404, FRONTEND)).toBe("next-segment-prefetch-404");
  });

  it.each([
    ["a different status", `${FRONTEND}/portfolio/__next.!KGRhc2hib2FyZCk.portfolio.txt?_rsc=a`, 500],
    ["the API origin", "http://localhost:8080/portfolio/__next.!KGRhc2hib2FyZCk.portfolio.txt?_rsc=a", 404],
    ["a mismatched route segment", `${FRONTEND}/portfolio/__next.!KGRhc2hib2FyZCk.overview.txt?_rsc=a`, 404],
    ["another route group", `${FRONTEND}/portfolio/__next.!KGF1dGgp.portfolio.txt?_rsc=a`, 404],
    ["no _rsc query", `${FRONTEND}/portfolio/__next.!KGRhc2hib2FyZCk.portfolio.txt`, 404],
    ["a JS chunk", `${FRONTEND}/_next/static/chunks/app.js`, 404],
    ["an API call", `${FRONTEND}/api/portfolio`, 404],
    ["a nested path", `${FRONTEND}/a/b/__next.!KGRhc2hib2FyZCk.b.txt?_rsc=a`, 404],
  ])("treats %s as unexpected", (_label, url, status) => {
    expect(classifyHttpFailure(url, status, FRONTEND)).toBe("unexpected");
  });
});

describe("classifyConsoleError", () => {
  const NOISE_URL = `${FRONTEND}/overview/__next.!KGRhc2hib2FyZCk.overview.txt?_rsc=q`;

  it("treats the browser's resource-404 message for a prefetch file as known noise", () => {
    expect(
      classifyConsoleError(
        "Failed to load resource: the server responded with a status of 404 (Not Found)",
        NOISE_URL,
        FRONTEND,
      ),
    ).toBe("next-segment-prefetch-404");
  });

  it("treats the same message for any other URL as unexpected", () => {
    expect(
      classifyConsoleError(
        "Failed to load resource: the server responded with a status of 404 (Not Found)",
        `${FRONTEND}/api/portfolio`,
        FRONTEND,
      ),
    ).toBe("unexpected");
  });

  it("treats any other console error, even at a prefetch URL, as unexpected", () => {
    expect(classifyConsoleError("Minified React error #418", NOISE_URL, FRONTEND)).toBe("unexpected");
    expect(
      classifyConsoleError(
        "Failed to load resource: the server responded with a status of 500 (Internal Server Error)",
        NOISE_URL,
        FRONTEND,
      ),
    ).toBe("unexpected");
  });
});
