# Backlog: Replace the unofficial Yahoo Finance price feed

**Status:** Mitigated (Option A shipped) — known fragility — 2026-06-21
**Owner:** unassigned
**Priority:** Medium (single point of failure for the entire market-data feed)

---

## Status & Decision

The production market-data feed depends on Yahoo Finance's **unofficial** quote
endpoint (`https://query1.finance.yahoo.com/v7/finance/quote`). On 2026-06-21 this
endpoint began returning **`401 Unauthorized`**, so every `market-data-refresh-job`
run fetched zero prices, published no `PriceUpdatedEvent`s, and exited `0` (the code
falls back to cached prices on provider failure). Downstream this left the Market
Data page stuck at "Last Updated: N days ago / 24h change —" and the AI Insights page
showing "No market data available yet" (insight-service prunes tickers not updated
within 24h).

**Mitigation shipped (Option A):** the client now performs Yahoo's cookie + crumb
handshake and sends a browser `User-Agent`, which is the current accepted way to use
this endpoint. This restores the feed **but keeps us on an unofficial, unstable API.**
This item tracks the durable fix: move to a supported, keyed market-data provider.

---

## Why This Is Fragile (do not consider it "done")

- **Unofficial/undocumented endpoint.** Yahoo has repeatedly changed the auth scheme
  (v6 removal, cookie+crumb requirement, intermittent 401/429). It can break again at
  any time with no notice or SLA. (Content rephrased for compliance; see references.)
- **Datacenter-IP risk.** Yahoo treats cloud egress IPs more harshly than residential
  ones. The Azure Container Apps (centralindia) egress IP may get `401`/`429`/blocked
  even with a valid crumb. If that happens, the crumb handshake will **not** save us —
  only a different provider (or an egress with a cleaner IP) will.
- **Silent failure mode.** A provider failure is swallowed as a graceful fallback
  (exit `0`), so a green Job run does **not** prove fresh data landed. Staleness is
  only visible downstream. (See observability note below for the metric to alert on.)

## Current mitigation — what was implemented (Option A)

- `market-data-service/.../YahooFinanceExternalMarketDataClient.java`
  - Lazy `ensureSession()`: GET `cookie-url` (default `https://fc.yahoo.com/`) to obtain
    the `A1` cookie, then GET `crumb-path` (default `/v1/test/getcrumb`) with that cookie
    to obtain the crumb; both cached behind a lock.
  - Every request carries a browser `User-Agent`; quote requests append `&crumb=` and the
    `Cookie` header.
  - On a `401`, the session is invalidated and the request is retried once; a persistent
    `401` propagates to the existing cached-price fallback.
- `market-data-service/.../ExternalMarketDataProperties.java` — new `userAgent`,
  `cookieUrl`, `crumbPath` settings (overridable under `external-market-data` in
  `application.yml`).
- Tests: `ExternalMarketDataClientWireMockTest` (handshake, crumb on quote, 401
  re-handshake retry, persistent-401 propagation) and `MarketDataRefreshJobWireMockTest`
  (handshake stubs so no real network).

## Recommended durable fix (Option C) — switch providers

Move to a supported market-data API with an API key and a published rate limit, behind
the existing `ExternalMarketDataClient` interface (the seam already exists, so this is a
localized change). Candidates to evaluate: Finnhub, Twelve Data, Financial Modeling Prep,
Alpha Vantage (note: AV free tier ~25 req/day is too low for ~161 tickers). Selection
criteria: free/cheap tier that covers ~161 symbols across US + NSE (`.NS`) equities,
crypto (`-USD`), and FX (`=X`); batch quote support; stable auth.

### Checklist
- [ ] Pick a provider and confirm symbol coverage for US equities, NSE (`.NS`), crypto, and FX.
- [ ] Add a new `ExternalMarketDataClient` implementation (e.g. `FinnhubExternalMarketDataClient`),
      selected via `external-market-data.provider`.
- [ ] Add the API key as a GitHub Actions secret and wire it through Terraform
      (`TF_VAR_*` → Container App secret env), mirroring existing secret handling. Keep it
      out of `application.yml` and any committed file.
- [ ] Map provider symbols to our ticker conventions (especially `.NS` and `=X`/FX pairs).
- [ ] Honor the provider's rate limits (batch or throttle; the Job fetches ~161 tickers).
- [ ] WireMock unit tests for the new client; keep the graceful-fallback contract
      (provider error → no publish, no overwrite).
- [ ] Decommission or demote the Yahoo client to a fallback once the new provider is validated.

## Observability / alerting (recommended regardless of provider)

The client already emits Micrometer counters that make silent failures visible — wire an
alert on these so we don't rediscover staleness via the UI:
- `market.data.refresh.outcome{result="provider_error"}` — a refresh fetched nothing.
- `market.data.provider.requests{outcome="http_error"|"unauthorized_retry"}` — provider rejecting us.
- `market.data.provider.session{outcome="error"|"incomplete"}` — handshake failing.

## Cross-References
- Client: `market-data-service/src/main/java/com/wealth/market/YahooFinanceExternalMarketDataClient.java`
- Config: `market-data-service/src/main/resources/application.yml` (`external-market-data`)
- Refresh path: `market-data-service/src/main/java/com/wealth/market/MarketDataRefreshService.java`
- Background on the cookie/crumb requirement: Yahoo locked down the quote endpoint; the
  cookie + crumb handshake is the community-established workaround (e.g. `node-yahoo-finance2`
  issue #764). Content rephrased for compliance with source licensing.
