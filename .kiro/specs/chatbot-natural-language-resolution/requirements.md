# Requirements Document

## Introduction

This spec defines the work to make the AI Insights chatbot (`POST /api/chat`, served by
`insight-service`) genuinely intelligent at understanding natural-language references to assets the
application knows about. Today the chatbot resolves tickers with a brittle regex/string pipeline and
only invokes the LLM (Azure OpenAI, deployment `gpt-4o-mini`) *after* a ticker has already been
resolved — purely to write a two-sentence sentiment blurb. As a result, ordinary phrasing fails:
"Apple" does not resolve to `AAPL`, "HDFC Bank" does not resolve to `HDFCBANK.NS`, "BTC" does not
map to the registry symbol `BTC-USD`, "BTCUSD" is dropped, and capability questions ("which stocks
can you tell me about?") return a generic clarification.

This feature repositions the LLM as the resolution and intent-understanding brain of the chat,
grounded in a name- and alias-aware catalog of the assets the app supports, while keeping all
factual price/trend data deterministic and sourced from Redis. The goal is a chat experience that
responds correctly to the way a real user asks questions, suitable as a portfolio/interview
showpiece.

## Problem Statement

Confirmed from code and live Azure logs:

- `ChatController.resolveTicker()` is a four-stage regex pipeline (dollar-tag, 1–5 uppercase
  letters, suffix `-USD`/`=X`/`.NS`, conversational stop-word filter). The LLM is not consulted
  during resolution.
- Company/common names have no path to tickers. The canonical dictionary
  (`config/seed-tickers.json`) contains only `{ticker, assetClass, quoteCurrency, basePrice}` — no
  human-readable names or aliases exist anywhere in the system.
- Bare crypto stems (`BTC`) and glued/slashed pairs (`BTCUSD`, `BTC/USD`) do not map to registry
  symbols (`BTC-USD`). The same gap applies to forex (`USDCHF`, `USD/CHF` → `USDCHF=X`).
- There is no concept of conversational intent (asset query vs. discovery/list vs. greeting);
  every message is forced through a single "extract exactly one ticker" funnel.
- The explicit `request.ticker` field is uppercased and used without validation against the known
  asset universe.
- The deployed Azure OpenAI deployment is named `gpt-4o-mini`; the platform reports its backing
  model as `gpt-4.1-mini` (version `2025-04-14`). The deployment name is the configuration contract.
  The limitation is architectural, not model capacity.

## Goals

- Resolve natural-language asset references (official names, common names/aliases, tickers, and
  bare/glued/slashed crypto and forex symbols) to the correct supported asset.
- Use the LLM for intent classification and entity resolution, grounded in a name/alias-aware
  catalog, so resolution reflects real user phrasing — while bypassing the LLM for trivially
  resolvable inputs to control cost.
- Keep all prices and trends deterministic and sourced from Redis — the LLM must never fabricate
  market data — and present them in the asset's quote currency.
- Handle discovery/capability intents ("what can you tell me about?") with a useful, grounded,
  bounded response.
- Degrade gracefully and safely when the LLM or market data is unavailable.

## Non-Goals

- Adding a new external market data provider or changing the `PriceUpdatedEvent` contract.
- Re-architecting the gateway, auth, portfolio, or market-data services.
- Personalised financial advice or buy/sell recommendations (explicitly out of scope, consistent
  with the existing advisor system prompt).
- **Server-side multi-turn conversational memory / session state** — the endpoint remains stateless
  this phase (see Requirement 10 for the stateless follow-up behaviour).
- **Full multi-asset comparison** (e.g. "compare AAPL and MSFT" with side-by-side metrics) — the
  comparison intent is *recognized* this phase but answered with a graceful single-asset redirect
  (Requirement 5); full comparison is deferred to a future phase.
- Frontend redesign beyond rendering the responses already returned by `POST /api/chat`.
- Changing the deployed model tier; remaining on the configured `gpt-4o-mini` deployment.

## Glossary

- **insight-service**: Spring Boot service hosting the chat endpoint and the Azure OpenAI adapters.
- **ChatController**: REST controller for `POST /api/chat`; currently owns regex ticker resolution.
- **Supported catalog universe**: The set of assets the application knows how to talk about — the
  160 canonical entries derived from `config/seed-tickers.json`, enriched with name and aliases.
  Resolution may only map to assets in this universe.
- **Active market-data universe**: The subset of supported assets that currently have price data in
  Redis (`market:tracked-tickers` ZSET / `market:latest:*`). Factual price/trend answers come only
  from here.
- **Ticker catalog**: The name/alias-aware view of the supported catalog universe (ticker + display
  name + aliases + asset class + quote currency) used to ground LLM resolution. Introduced by this
  spec.
- **Resolution**: Mapping a user's natural-language reference to zero or more supported assets.
- **Deterministic preflight**: A non-LLM resolution stage that handles explicit tickers and exact
  canonical symbols before any LLM call.
- **Intent**: The classified purpose of a user message (asset query, discovery/list,
  comparison, greeting/help, unknown).
- **Grounding**: Constraining the LLM to the ticker catalog so it cannot resolve to or invent
  symbols outside the supported universe.
- **LLM**: Azure OpenAI chat model accessed via Spring AI `ChatClient` using the configured
  deployment (`gpt-4o-mini`).

## Requirements

### Requirement 1: Natural-Language Asset Resolution

**User Story:** As a user, I want to refer to an asset the way I naturally would — by company name,
common name, or ticker — so that the chatbot understands me without my knowing the exact internal
symbol.

#### Acceptance Criteria

1. WHEN a user message references a supported asset by its official or common name or a known alias
   (e.g. "Apple", "HDFC Bank", "Reliance", "Berkshire", "Bitcoin"), THE chatbot SHALL resolve it to
   the correct supported ticker (e.g. `AAPL`, `HDFCBANK.NS`, `RELIANCE.NS`, `BRK-B`, `BTC-USD`).
2. WHEN a user references a cryptocurrency by name or bare stem (e.g. "Bitcoin", "BTC"), THE
   chatbot SHALL resolve it to the supported registry symbol (e.g. `BTC-USD`).
3. WHEN a user references an asset by a glued or slashed pair format (e.g. "BTCUSD", "BTC/USD",
   "USDCHF", "USD/CHF"), THE chatbot SHALL resolve it to the supported registry symbol where one
   exists (e.g. `BTC-USD`, `USDCHF=X`).
4. WHEN a user provides an exact supported symbol (e.g. `AAPL`, `BTC-USD`, `USDCHF=X`,
   `RELIANCE.NS`), THE chatbot SHALL resolve it correctly (no regression of the
   chatbot-asset-coverage-fix behaviour).
5. WHEN the explicit `ticker` request field is present and non-blank, THE service SHALL normalize
   and validate it against the supported catalog universe and use it with highest precedence. An
   explicit ticker that is NOT in the supported universe SHALL produce a **clarification** response;
   a supported explicit ticker that has NO active Redis data SHALL produce a **no-data** response.
   In neither case SHALL the service perform arbitrary resolution.
6. THE resolution SHALL only ever resolve to a ticker that is present in the **supported catalog
   universe**; THE chatbot SHALL NOT resolve to or report on a symbol outside that universe.
7. THE catalog SHALL support multiple aliases per asset (e.g. `BRK-B` → "Berkshire Hathaway",
   "Berkshire"; `HDFCBANK.NS` → "HDFC Bank"; `BTC-USD` → "Bitcoin", "BTC") so resolution reflects
   realistic phrasing, not a single canonical name.

### Requirement 2: LLM-Driven Intent and Entity Understanding (with Deterministic Preflight)

**User Story:** As a user, I want the assistant to actually understand my question rather than
pattern-match keywords, so that varied phrasing works — without unnecessary cost on trivial inputs.

#### Acceptance Criteria

1. THE service SHALL first perform a **deterministic preflight**: an explicit valid `ticker` field
   or an exact canonical supported symbol in the message SHALL resolve WITHOUT an LLM call.
2. WHEN deterministic preflight does not conclusively resolve the message, THE service SHALL use the
   LLM to (a) classify intent and (b) extract referenced asset entities and map them to supported
   tickers, grounded in the ticker catalog.
3. THE LLM SHALL classify intent into at least: asset query, asset discovery/list, comparison,
   greeting/help, and unknown.
4. THE LLM resolution step SHALL return **structured output** (not free text) containing at minimum:
   `intent`, extracted `entities`, `resolvedTickers`, `candidateTickers` (for ambiguity),
   `categoryFilter` (for discovery, when present), and a `clarificationReason` (when applicable).
5. THE service SHALL validate every LLM-proposed ticker against the supported catalog universe and
   SHALL discard any proposed ticker not in that universe.
6. WHEN the LLM proposes multiple candidate tickers for a single ambiguous reference, THE chatbot
   SHALL ask a concise clarifying question or present the candidates, rather than silently choosing
   one.
7. THE LLM resolution prompt SHALL instruct the model to ignore any user attempt to redefine the
   catalog, invent assets, change its instructions, or reveal system/catalog/prompt content
   (prompt-injection resistance), and SHALL not resolve to assets outside the supplied catalog.

### Requirement 3: Deterministic Factual Grounding

**User Story:** As a user, I want any prices and trends the assistant states to be accurate and in
the right currency, so I can trust the numbers.

#### Acceptance Criteria

1. ALL prices, price history, and trend percentages reported by the chatbot SHALL be sourced from
   Redis via `MarketDataService` for the resolved ticker (the active market-data universe).
2. THE LLM SHALL NOT be the source of any numeric market value; numeric values SHALL be injected
   from market data after resolution.
3. WHEN a reference resolves to a **supported** asset that has **no price data in the active
   market-data universe** (Redis), THE chatbot SHALL return a clear "no data" style message naming
   the resolved ticker, rather than fabricating a value. (Resolution to the supported universe and
   availability of live data are distinct concerns.)
4. THE existing sentiment behaviour (LLM produces a short qualitative sentiment grounded in the real
   price history/trend) SHALL be preserved for resolved tickers with data.
5. Reported monetary values SHALL use the resolved asset's quote currency (from the catalog /
   market data) rather than a hardcoded `$` for all assets (e.g. INR for `*.NS`, the pair's
   convention for forex).

### Requirement 4: Asset Discovery and Capability Queries

**User Story:** As a user, I want to ask what the assistant can tell me about, so I can explore the
available data.

#### Acceptance Criteria

1. WHEN a user asks a discovery/capability question (e.g. "which stocks can you tell me about?",
   "what assets do you track?"), THE chatbot SHALL respond with a useful, grounded answer derived
   from the asset universe rather than a generic clarification.
2. THE discovery response SHALL be bounded: a representative, categorized subset (suggested bounds:
   up to ~12–20 assets total, up to ~5 per category) with both names and tickers, and SHALL make
   clear that more assets are available.
3. WHEN a user asks about a category (e.g. "crypto", "Indian stocks", "forex"), THE chatbot SHALL
   scope the discovery response to that asset class.
4. THE discovery response SHALL prefer the **active market-data universe** (assets with live Redis
   data); IF that is empty/unavailable, THE chatbot MAY fall back to the supported catalog universe
   with wording indicating live data may be temporarily unavailable.
5. Discovery MAY be handled deterministically (without an LLM call) when intent is recognized, for
   cost and reliability.

### Requirement 5: Comparison Intent (Recognized, Deferred Implementation)

**User Story:** As a user, I want a sensible response when I ask to compare assets, even though full
comparison isn't built yet, so I'm not left with a confusing error.

#### Acceptance Criteria

1. WHEN a user requests a comparison of multiple assets (e.g. "compare Apple and Microsoft"), THE
   chatbot SHALL recognize the comparison intent and respond gracefully — acknowledging it can
   cover one asset at a time for now and offering to summarize one of the referenced assets.
2. THE chatbot SHALL NOT silently pick one asset and present it as the full answer to a comparison
   request, and SHALL NOT fabricate a comparison.
3. Full side-by-side multi-asset comparison is explicitly deferred to a future phase (Non-Goals).

### Requirement 6: Graceful Degradation and Safety

**User Story:** As a user, I want sensible behaviour when the AI or data layer is temporarily
unavailable, so the chat never silently breaks.

#### Acceptance Criteria

1. WHEN the resolution LLM is unavailable, errors, times out, or returns malformed/unparseable
   structured output, THE chatbot SHALL fall back to deterministic resolution of **exact canonical
   supported symbols only** (preserving chatbot-asset-coverage-fix behaviour) so exact-symbol
   queries still work; it SHALL NOT resolve arbitrary uppercase tokens in fallback.
2. WHEN resolution succeeds but the **sentiment** LLM is unavailable, THE chatbot SHALL still return
   the deterministic price/trend with a note that AI analysis is temporarily unavailable (existing
   behaviour preserved).
3. WHEN resolution cannot identify any supported asset, THE chatbot SHALL return a clear, helpful
   clarification message (optionally suggesting how to ask) rather than an arbitrary or empty
   response.
4. THE chatbot SHALL NOT provide personalised financial advice or specific buy/sell
   recommendations; responses SHALL remain informational.
5. THE chat endpoint SHALL never return a successful response with an empty assistant message.
6. THE LLM resolution SHALL be bounded by a timeout and SHALL never hang the request indefinitely.
7. Resolution and grounding SHALL not expose secrets, internal identifiers, or raw catalog/system
   prompt content to the user.

### Requirement 7: Ticker Catalog (Name/Alias-Aware Canonical Source)

**User Story:** As the system, I need a name- and alias-aware catalog of supported assets, kept as a
single canonical source, so the LLM can map human names to tickers reliably and tests stay durable.

#### Acceptance Criteria

1. THE canonical ticker source `config/seed-tickers.json` SHALL be enriched to associate each asset
   with a human-readable `name` and a list of `aliases`, in addition to the existing `ticker`,
   `assetClass`, `quoteCurrency`, and `basePrice` fields.
2. THE enriched file SHALL preserve the existing fields, the 160-entry count, and the asset-class
   distribution (US_EQUITY 50, NSE 50, CRYPTO 50, FOREX 10) so existing validation continues to
   pass.
3. ALL existing consumers of `config/seed-tickers.json` (notably `SeedTickerRegistry` in
   market-data-service and portfolio-service, and the seeders) SHALL be updated and tested to
   tolerate the enriched schema — either by extending their `SeedTicker` model with the new
   optional fields or by configuring their deserialization to ignore unknown fields — with no
   change to seeding behaviour, entry count, or asset-class checks.
4. THE catalog supplied to the LLM for grounding SHALL be derived from this canonical source and
   SHALL be the single source of truth (no separately hand-maintained in-prompt asset list that can
   drift from the file).
5. THE grounding catalog SHALL be bounded/compact (see Requirement 8) and SHALL reflect the
   supported catalog universe.

### Requirement 8: Performance and Cost Control

**User Story:** As the operator, I want the intelligent chat to remain responsive and inexpensive,
so it is viable on the demo budget (Azure Container Apps, scale-to-zero).

#### Acceptance Criteria

1. NATURAL-language resolution SHALL use at most **one** LLM call per user message; deterministic
   preflight (Requirement 2.1) and deterministic discovery (Requirement 4.5) MAY bypass the LLM
   entirely. The resolution path SHALL NOT fan out into multiple sequential LLM calls per message.
2. THE service SHALL cache or short-circuit resolution where reasonable (e.g. exact symbols resolved
   without an LLM call; repeated identical references served from a bounded cache) to limit token
   spend.
3. THE grounding catalog SHALL include only fields needed for resolution — ticker, name, aliases,
   asset class, quote currency — and SHALL NOT include `basePrice`, price history, or any Redis
   market values. The catalog SHALL be built once (e.g. at startup) and reused, not reconstructed
   per request.
4. THE resolution LLM call SHALL have a bounded maximum output size and a bounded timeout
   (documented in design) to cap latency and cost.
5. END-to-end chat response time SHALL remain acceptable for an interactive demo under normal
   (warm) conditions; ACA scale-from-zero cold starts are a separate, known concern outside this
   spec.

### Requirement 9: Observability and Test Coverage

**User Story:** As a maintainer/presenter, I want to trace how a message was understood and to lock
in behaviour with tests, so the feature is debuggable and regression-safe.

#### Acceptance Criteria

1. THE chat flow SHALL emit structured logs capturing: classified intent, extracted entities,
   validated resolved ticker(s), candidate tickers (on ambiguity), resolution source
   (deterministic-preflight vs. LLM vs. fallback), fallback reason (when applicable), resolver
   latency, LLM success/failure/timeout, final response path (asset-query / discovery / comparison
   / clarification / no-data), and a catalog version/hash. Logs SHALL NOT include secrets, full
   prompt contents, or the raw full catalog.
2. THE insight-service test suite SHALL include resolution tests covering at minimum: explicit
   ticker validation (valid and invalid), exact symbol, exact suffixed symbol (`BTC-USD`,
   `USDCHF=X`, `RELIANCE.NS`), bare stem (`BTC` → `BTC-USD`), glued/slashed pair (`BTCUSD`,
   `BTC/USD`, and forex `USDCHF`/`USD/CHF`), official name (`Apple` → `AAPL`), common
   name/alias (`HDFC Bank` → `HDFCBANK.NS`), ambiguous alias, untracked/unsupported reference,
   category discovery filtering, comparison-intent redirect, and Redis no-data after a successful
   catalog resolution.
3. THE LLM-dependent logic SHALL be testable deterministically by mocking/stubbing the resolution
   client so tests do not require live Azure OpenAI calls; malformed/empty LLM output SHALL be
   covered.
4. THE deterministic preflight and fallback paths SHALL be covered by tests independent of the LLM.
5. THE enriched seed-file backward compatibility (Requirement 7.3) SHALL be covered by tests across
   the affected consumers.
6. CI SHALL execute the affected insight-service (and any affected market-data/portfolio seed)
   test targets, and the feature SHALL ship through the restored deploy pipeline
   (PR → CI → merge → auto-deploy).

### Requirement 10: Stateless Follow-Up Behaviour

**User Story:** As a user who asks a follow-up like "how about its trend?", I want a sensible
response, even though the assistant doesn't remember previous turns this phase.

#### Acceptance Criteria

1. THE chat endpoint SHALL remain stateless this phase; THE service SHALL NOT maintain server-side
   conversation memory.
2. WHEN a message contains only a deictic/follow-up reference ("it", "that", "its trend") with no
   resolvable asset and no explicit `ticker` field, THE chatbot SHALL ask a concise clarification
   question rather than guessing.
3. THE design SHALL NOT preclude a future client-supplied context mechanism (e.g. the frontend
   passing the last resolved `ticker`), but such a mechanism is out of scope for this phase.

### Requirement 11: Model Configuration Portability

**User Story:** As the operator, I want the model choice to be configuration-driven, so we can
change tiers later without code changes.

#### Acceptance Criteria

1. THE implementation SHALL use the configured Azure OpenAI chat deployment (env-driven, defaulting
   to `gpt-4o-mini`) and SHALL NOT hard-code model-specific behaviour that would prevent a future
   deployment/tier change.
2. THE resolution capability SHALL sit behind a port/interface so the LLM provider/deployment can
   change without altering `ChatController` or the response-building logic.

## Traceability and Demo-Readiness

1. THIS feature SHALL maintain `requirements.md`, `design.md`, and `tasks.md` in this spec folder.
2. Each implementation task SHALL map to one or more requirements above.
3. Acceptance SHALL be demonstrable on the live Azure deployment with the queries that currently
   fail — "Apple", "HDFC Bank", "BTC", "BTCUSD", "which stocks can you tell me about?" — returning
   correct, grounded responses, and exact-symbol queries continuing to work.
