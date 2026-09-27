// Rehearsal snapshot: reads GET /api/portfolio through the signed-in page's own session.
// Paste into the page (browser-pane javascript tool) on https://vibhanshu-ai-portfolio.dev.
// The token stays inside this closure: it is never returned, logged or stored.
//
// Returns the checks, a digest, and `canonical`: every holding as "TICKER=quantity" (the exact
// quantity text the API returned), sorted by ticker, joined with "\n", no trailing newline.
// The operator saves `canonical` to a local file and runs verify-snapshot.py against `holdingsSha256`,
// so the saved snapshot is proven identical to what the API returned (see the operator script, step 0).
// Set BASELINE_SHA to the step-0 digest when running the post-restore comparison.
(async () => {
  const EXPECTED_EMAIL = "e2e-test-user@vibhanshu-ai-portfolio.dev";
  const BASELINE_SHA = null;
  const raw = localStorage.getItem("wmpt.auth.session");
  if (!raw) return { ok: false, reason: "no-session", url: location.href };
  let s;
  try { s = JSON.parse(raw); } catch { return { ok: false, reason: "session-unparseable" }; }
  const token = typeof s.token === "string" ? s.token : (typeof s.access_token === "string" ? s.access_token : null);
  if (!token) return { ok: false, reason: "no-token-in-session" };
  const emailMatches = s.email === EXPECTED_EMAIL;
  const res = await fetch("https://api.vibhanshu-ai-portfolio.dev/api/portfolio", {
    method: "GET", headers: { Authorization: "Bearer " + token, "Content-Type": "application/json" }, cache: "no-store",
  });
  const text = await res.text();
  if (!res.ok) return { ok: false, reason: "http-" + res.status, emailMatches };
  // Keep each quantity as the exact source text (JSON.parse source-text access, Chromium 114+).
  let sourceAccess = false;
  const body = JSON.parse(text, function (k, v, ctx) {
    if (k === "quantity" && ctx && typeof ctx.source === "string") { sourceAccess = true; return { __src: ctx.source }; }
    return v;
  });
  const portfolios = Array.isArray(body) ? body : [body];
  const out = { ok: true, at: new Date().toISOString(), url: location.href, status: res.status, emailMatches, sourceAccess, portfolioCount: portfolios.length };
  if (portfolios.length !== 1) return out;
  const p = portfolios[0];
  out.version = p.version === undefined ? null : p.version;
  const holdings = (p.holdings || []).map(h => {
    const q = h.quantity;
    let quantity, kind;
    if (q && typeof q === "object" && "__src" in q) {
      quantity = q.__src; kind = quantity.startsWith('"') ? "json-string" : "json-number";
      if (kind === "json-string") quantity = JSON.parse(quantity);
    } else if (typeof q === "string") { quantity = q; kind = "json-string"; }
    else { quantity = null; kind = "unverifiable-" + typeof q; }
    return { ticker: h.assetTicker, quantity, kind };
  }).sort((a, b) => a.ticker < b.ticker ? -1 : a.ticker > b.ticker ? 1 : 0);
  out.holdingCount = holdings.length;
  out.distinctTickers = new Set(holdings.map(h => h.ticker)).size;
  out.allQuantitiesExact = holdings.every(h => h.quantity !== null);
  out.kinds = [...new Set(holdings.map(h => h.kind))];
  const canonical = holdings.map(h => h.ticker + "=" + h.quantity).join("\n");
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(canonical));
  out.holdingsSha256 = [...new Uint8Array(digest)].map(b => b.toString(16).padStart(2, "0")).join("");
  if (BASELINE_SHA) out.matchesBaseline = out.holdingsSha256 === BASELINE_SHA;
  out.canonical = canonical;
  return out;
})()
