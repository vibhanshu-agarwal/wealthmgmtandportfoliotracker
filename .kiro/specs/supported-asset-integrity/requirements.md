# Requirements Document

> **Revision 13 — 2026-08-15.** Narrow amendment from design review. Requirement 7 gains an explicit
> exemption for **fabricated** history: the legacy `BTC` `market_price_history` rows are synthetic V2
> seed values for a symbol never tracked against a provider, so canonical continuity does not apply to
> them and they are not merged into `BTC-USD`. They are also not left in operational history — that
> would leave invented observations queryable by every consumer of the table, the same defect as
> merging, differing only in which ticker carries it. They are archived verbatim with reason
> `LEGACY_SYNTHETIC` and removed. `MM.NS` history continues to migrate normally: its observations are
> real and only its symbol was wrong.
>
> **Revision 12 — 2026-08-15.** Incorporates tenth-pass review. Requirement 9's activation contract
> is made self-consistent: 9.7's blanket "SHALL NOT activate" contradicted 9.9's early-activation
> allowance, so 9.7 now reads "except under the explicit waiver in 9.9", and 9.9 is restated as that
> waiver — applicable only to backlog drainage, with 9.7a and 9.7b declared non-waivable, since
> activating before the producer is narrowed or the repairs have landed would reject events the
> system is still expected to produce. Lag-zero evidence is tightened: it must be observed after
> every producer revision capable of emitting the emergent set has stopped, and across every
> partition of the projection's consumer group, because a momentary zero can precede a final
> in-flight publication and one lagging partition suffices to carry it. Seven references that
> resolved syntactically but pointed at the wrong criteria were corrected.
>
> **Revision 11 — 2026-08-15.** Incorporates ninth-pass review. The Requirement 9 cutover is made
> complete: an event whose ticker is absent from the Supported_Catalog is now handled explicitly and
> independently of its quote currency (previously an out-of-catalog event carrying a *non-null*
> currency fell through undefined), and activation is gated on three conditions rather than
> deployment order alone — Requirement 5 narrowing the desired set, Requirement 7's repairs
> completing, and the pre-cutover Kafka backlog draining or expiring, the last established by
> evidence rather than assumed. Deploying Requirement 5 first stops *producing* out-of-catalog
> events but leaves already-published ones queued, which the previous constraint did not address.
> Requirement 11's title is qualified to the price-write invariant, and its one forward-looking
> criterion is removed rather than left "vacuously satisfied" — untraceable in SDD terms — with the
> constraint migrated to D20, which binds `asset-picker-composition` where it can actually be tested.
> The remaining broad phrase in the introduction's evidence chain is narrowed to match D16.
>
> **Revision 10 — 2026-08-15.** Self-consistency audit, no external review. Four findings, all
> internal contradictions rather than new material. Requirement 11's blanket "Status: satisfied"
> overclaimed, because one criterion bound a demo reset mechanism that does not exist; the header was
> narrowed to the price-write invariant and the two criteria that lost satisfaction notes in the
> Revision 8 rewrite regained them. (Revision 11 went further and removed the forward-looking
> criterion entirely — see D20.) Non-goal 12.6 forbade
> changing `insight-service` while Requirement 10 changes its load-failure behaviour — both hold,
> and the distinction is now stated. Non-goal 12.8 was aligned with the narrowed status. A missing
> ordering constraint was added covering activation ordering for the currency-rejection rules. (Revision 11 replaced it:
> deployment ordering alone proved insufficient — see below.) All seven line-numbered code citations re-verified against `main`
> post-merge; all correct.
>
> **Revision 9 — 2026-08-15.** Incorporates eighth-pass review, closing two gaps the Revision 8
> enforcement split introduced. Write paths are remodelled as **composable layers** rather than
> mutually exclusive kinds: an Application_Operation validates and raises a typed failure with
> rollback, an Http_Entry_Point maps that failure to 422, and a Direct_Caller observes it without
> HTTP semantics. The Golden_State_Seeder is both — `PortfolioSeedController` invokes it over HTTP
> in production — which the previous model denied. The Post_Migration_Integrity_Assertion is
> strengthened to check the New_Write_Invariant on migration-created holdings **as well as** the
> Referential_Invariant on the store, since the latter alone admits deprecated assets and would let
> a migration create a new Deprecated_Position and pass; it is also made a blocking gate rather than
> a diagnostic. One remaining service-wide phrase in the evidence chain narrowed to the seed path.
>
> **Revision 8 — 2026-08-15.** Incorporates seventh-pass review. Two scope contradictions removed.
> Requirement 11's price-write prohibition is narrowed to **seed and reset capabilities**; a blanket
> ban would have forbidden `MarketPriceProjectionService`, the legitimate Kafka projection that
> Requirement 9 mandates, making the two requirements mutually unsatisfiable. The claim that
> `portfolio-service` holds no JDBC collaborator was an overstatement of a seed-path fact and is
> corrected wherever it appeared, including D16. Separately, Write_Boundary now distinguishes
> writer classes by enforcement mechanism (superseded by the layering in Revision 9): the invariant is uniform, the
> enforcement mechanism is not, because Flyway can neither load the Catalog_Module nor return an
> HTTP status. Two evidence statements corrected: the FX-skip observation no longer asserts
> causation, and Observation_Timestamp is defined as producer processing time assigned per ticker
> after the batch fetch rather than at each ticker's own fetch.
>
> **Revision 7 — 2026-08-15.** Incorporates sixth-pass review. The dated-event projection becomes
> one transaction, so a surfaced history conflict leaves both tables unchanged; non-null quote
> currencies are validated against the Canonical_Manifest rather than accepted unchecked; the
> undated-event history rule is stated explicitly, forbidding a Receive_Time substitute; and the
> "oldest contributing" field is renamed and redefined over all held assets with a known timestamp.
> The refresh "steady state" claim is replaced by two dated observations — the scheduled
> 2026-08-15 08:00 UTC run (`updated=159, skipped=2, failed=0`, skips `TATAMOTORS.NS` and `MM.NS`)
> and a manual sweep two hours earlier (`156/5/0`) whose three extra FX skips had all recovered —
> establishing that FX skips rotate and are transient, and refusing to promote either run into a
> count invariant.
>
> **Revision 6 — 2026-08-15.** Incorporates fifth-pass review, plus a self-audit. Review items:
> price-state preservation split per store shape (the blanket rule demanded Mongo-only fields of
> Postgres and preservation of a row it also removes); Observation_Timestamp redefined as
> producer-recorded successful-fetch time, since the Yahoo DTO carries no timestamp to propagate;
> nullable `quoteCurrency` normalization specified before tuple comparison, resolving from the
> catalog and rejecting rather than defaulting; runtime history writes given the same
> identical-versus-conflicting rule as the latest row; Requirement 2.4 extended to non-blank ticker,
> asset class, quote currency and positive `basePrice`; glossary pointer corrected.
> **Self-audit additions:** Requirement 3 gains a manifest-migration ordering constraint — no
> `lifecycleStatus` field exists on any of the 160 entries today, so shipping its validation first
> would fail startup in every catalog-consuming service at once; a dangling `Effective_Universe`
> reference and an unused `Symbol_Correction` term were resolved.
>
> **Revision 5 — 2026-08-15.** Incorporates fourth-pass review. Three architectural decisions made:
> the freshness contract is **narrowed and renamed to asset-price freshness**, with FX rate age
> explicitly excluded and recorded as a follow-up rather than silently omitted; the null-over-null
> projection case becomes a deterministic rule instead of `MAY`, with its conflict behaviour and
> accepted limitation stated; and collision ties are defined for equal known timestamps in Postgres
> and for Mongo, naming `updatedAt` as the ordering field and the five-field document tuple. Three
> corrections: the `BTC` narrative said the symbol had no price row when `V2` seeds one — the
> accurate claim is an orphaned legacy row that can never refresh; "all four counts" corrected to
> three; and the price-state preservation rule now scopes to every price-state repair.
>
> **Revision 4 — 2026-08-15.** Incorporates third-pass review. Three unsatisfiable or undefined
> cases resolved: null Observation_Timestamp transition semantics (Requirement 9), collision
> handling for both price stores rather than holdings alone (Requirement 7), and the removal of a
> quantity/cost-basis preservation clause that named price rows having no such fields. Two contract
> gaps closed: holding-count assertions now compare against `Active_Asset` cardinality with the
> Catalog_Version asserted separately, and the freshness response gains an `Unknown_Price_Holding`
> count with defined semantics for unknown timestamps and for an empty portfolio.
>
> **Revision 3 — 2026-08-15.** Incorporates second-pass review. Requirement 11 is **delivered** —
> PR #97 is merged and deployed — and is retained as a regression boundary rather than removed.
> Requirement 9 gains tuple-atomic projection semantics and a defined `MISSING`/`UNKNOWN`
> separation with aggregate precedence. Requirement 7 splits PostgreSQL repairs (Flyway) from the
> Mongo repair, which cannot be Flyway-idempotent. Requirement 11's production isolation is
> strengthened from route separation to absence in production profiles. Fixed-count assertions in
> live monitoring are replaced by catalog-derived ones. `basePrice` is included in the
> Catalog_Version. The incident narrative moves to past tense and the BTC comparison is re-derived
> against a verified provider price.
>
> **Revision 2 — 2026-08-15.** Three blocking items resolved: production reachability of the
> Golden_State_Seeder established as fact; Requirement 9 rewritten around an authoritative
> observation timestamp; the contradiction between the Catalog_Version's "every behaviourally
> relevant field" rule and the seeder's need for `basePrice` resolved by a seed-only interface
> (criteria numbers of that era have since shifted; see Requirements 2 and 11 as they now stand). Requirement 10 widened to all catalog consumers and all normal profiles.
> Requirements 5, 6, and 7 name four invariants separately rather than conflating them.

## Introduction

A user of this application cannot currently create a holding that the system is capable of pricing, except by accident. `PortfolioService.addHolding` (`PortfolioService.java:57–77`) accepts the `ticker` field of its request body as an opaque string, persists it, and returns 201. There is no validation against any list, in any layer. The database does not constrain it either: `asset_ticker` is `VARCHAR(20) NOT NULL` with a `UNIQUE (portfolio_id, asset_ticker)` and no foreign key (`V1__Initial_Schema.sql:23–25`). Cost-basis capture looks the ticker up in `market_prices` and, on a miss, leaves the cost-basis fields null and completes successfully (`PortfolioService.java:84–103`). Every layer is permissive, so the only thing that has kept holdings priceable is that no user-facing path has ever created one.

The planned Asset Picker is that path. It is therefore not primarily a UI feature: it is the first mechanism that will let arbitrary users write holdings, and it will do so against a write boundary that validates nothing. This spec exists because the picker cannot be built safely on that boundary, and because the failure it would industrialise is already present in production.

The known instance is the demo portfolio. It holds `BTC` (0.75 units). The canonical crypto symbol in this system is `BTC-USD`; `V12__Backfill_Market_Price_History.sql` canonicalised market data to it and said so in its own comment, but V3's *holding* was never migrated. `MarketDataRefreshService.resolveTrackedTickers()` (`MarketDataRefreshService.java:144–160`) returns the configured baseline **union everything already present in Mongo**, and `BTC` is in neither, so it is not failing to refresh — it is invisible to the refresh pipeline and cannot be a candidate for it. The holding is consequently valued at `70,775.00`, the price seeded by `V2__Seed_Market_Data.sql` in April 2026, while `GET /api/portfolio/summary` returns `partialValuation: false` — it asserts the valuation is complete. `PortfolioService` sets that flag `true` only when an FX rate is unavailable (`PortfolioService.java:~134–146`); there is no staleness check on `current_price` or `updated_at` anywhere in the codebase, so a price from four months ago is indistinguishable from one from two minutes ago.

The overstatement was re-derived on 2026-08-15 against a provider price verified after the seeder defect below was fixed and deployed. A real refresh (`updated=156, skipped=5, failed=0`) persisted `BTC-USD` at `62,988.42`. The demo holding is valued at `0.75 × 70,775.00 = 53,081.25` where the correct figure is `0.75 × 62,988.42 = 47,241.32`: an **overstatement of `$5,839.93`, or 9.8% of the reported `$59,490.57` total**.

Prior analysis reached `$5,872.88` against a `BTC-USD` of `62,944.49`. That earlier observation was briefly suspected of having recorded a synthetic price rather than a real quote, because production prices were being overwritten daily (below). It did not: the removed seeder's deterministic formula would have written `BTC-USD 68,106.3200`, `AAPL 197.3396`, and `TSLA 183.2891` for the E2E user, against observed values of `62,944.49`, `305.93`, and `342.27`. The claim supported by that arithmetic is the narrow one — **those specific observations were not generated by the removed seeder** — not the broader claim that no price was ever contaminated. Today's independent refresh corroborates the defect's magnitude. The defect does not depend on the figure in any case: `BTC` holds an **orphaned legacy price row** — `V2__Seed_Market_Data.sql` inserts `('BTC', 70775.0000, now())` — which the valuation reads and the refresh can never update, because `BTC` is absent from the tracked set. It is not a missing price; it is a permanently frozen one, which is why it reports as complete rather than partial. Requirement 7.2 removes that row.

The distinction that constrains any remedy: falling back to the last known price when a provider call *fails* is intended behaviour and is preserved. Valuing on a price that can **never** update, while reporting completeness, is not. Excluding stale holdings from `totalValue` was considered and rejected in prior analysis — the requirement is to show the older price, not to drop the holding.

**Production market prices were being overwritten daily by the test seeder.** `.github/workflows/synthetic-monitoring.yml` contains two jobs. The one gated `vars.CLOUD_PROVIDER == 'aws'` does not run; the one gated `vars.CLOUD_PROVIDER == 'azure'` did, because the repository variable is `azure`. That job sets `API_BASE: https://api.vibhanshu-ai-portfolio.dev` and POSTs to `/api/internal/portfolio/seed`, on `cron: '0 8 * * *'`. That endpoint invoked `PortfolioSeedService.seed()`, which batch-upserted 160 rows into `market_prices` — a global table keyed by ticker with no user scoping — using synthetic values from `DeterministicPriceCalculator.compute(basePrice, ticker, userId)`, followed by 160 `market_price_history` rows, under an unconditional `DO UPDATE` with no freshness guard. The market-data refresh ACA Job runs on the *same* cron, so the two raced daily and the winner determined whether production carried real or synthetic prices for the next 24 hours.

The near-miss is instructive. The same workflow sets `SKIP_MARKET_DATA_SEED: "true"` and its step comment read "Market-data seed is gated off in prod/azure (ACA Job)" — the market-data seeder was correctly identified as a price writer and gated. The portfolio seeder wrote prices as an undeclared side effect of seeding holdings, and was not.

**This is fixed.** PR #97 merged as `b49ed01` and deployed on 2026-08-15; the **seed path** in `portfolio-service` writes portfolios and holdings only, in every profile, and `PortfolioSeedService` no longer holds a JDBC collaborator at all. That scoping matters: `portfolio-service` as a whole legitimately writes both price tables through `MarketPriceProjectionService`, which holds its own `JdbcTemplate` and is driven by the Kafka `PriceUpdatedEventListener`. That projection is the mechanism Requirement 9 governs and must be preserved; an earlier draft of this document overstated the fix as service-wide, which would have prohibited it. Requirement 11 therefore documents an **already-established invariant** and is retained as a regression boundary rather than as work to do. Closure rests on a chain of evidence, no link of which is sufficient alone: the deployed image SHA (`portfolio-service--0000054`, tag `b49ed010698abf8e04fda7bbc66bd3a5dfd7f8b8`, 100% traffic) → reviewed source containing no price-write path reachable from the seed endpoint or the seeder → a production log line proving that artifact executed a real seed (`Golden-state seed complete (holdings only): … holdings=160`, 06:20:37Z) → a full-table integration regression test asserting both price tables are byte-identical across two seed invocations, with sentinel rows to catch a wholesale rewrite.

The refresh pipeline itself is healthy and is not the cause of any of this. Cadence is daily at 08:00 UTC via `azurerm_container_app_job.market_data_refresh` (`cron_expression = "0 8 * * *"`); the in-service hourly `@Scheduled` adapter is disabled in Azure. The scheduled run of **2026-08-15 08:00 UTC** reported `updated=159, skipped=2, failed=0`, the two skips being `TATAMOTORS.NS` and `MM.NS` — the two symbols Requirement 4 corrects. That is a dated observation, not a count invariant: an ad-hoc manual sweep two hours earlier the same day reported `updated=156, skipped=5, failed=0`, the three additional skips being `USDJPY=X`, `USDCAD=X`, and `USDHKD=X`, all of which recovered by the scheduled run while `USDINR=X` — previously the symbol seen skipping — succeeded in both. FX skips therefore **rotate** and are transient rather than symbol breakage. That is what the recovery establishes. The pattern is *consistent with* transient provider throttling following closely spaced manual sweeps, but recovery alone does not establish that cause, and no requirement here depends on it. No requirement here asserts a fixed updated/skipped count, for the same reason Requirement 3.4 refuses to assert a fixed catalog size. On a provider miss the job skips, keeps the last known price, and does not throw. All of that is deliberate and out of scope for change.

The reason the effective universe is emergent rather than declared is structural. `config/seed-tickers.json` holds **160** entries (50 `US_EQUITY`, 50 `NSE`, 50 `CRYPTO`, 10 `FOREX`), each with `ticker`, `name`, `aliases`, `assetClass`, `quoteCurrency`, `basePrice`. The refresh baseline in `market-data-service/src/main/resources/application.yml` holds **55**. The refresh set is therefore `55 ∪ historical Mongo contents`, which is a record of what was seeded in the past, not a statement of what the product supports. This is why two symbols that appear in no baseline persist in the refresh set: `MM.NS`, which is simply wrong (`M&M.NS` resolves to "MAHINDRA & MAHINDRA LTD"; the `&` was dropped, and the entry's own `aliases` already contains `"M&M"`), and `TATAMOTORS.NS`, which no longer resolves because Tata Motors demerged into `TMCV.NS` and `TMPV.NS`. A third skipped symbol, `USDINR=X`, was verified transient — it returns a live quote and failed once immediately after two manual full sweeps 18 minutes apart. It requires no action and must not be "fixed".

The catalog file is duplicated in four locations, byte-identical at `md5 a6caba55d3ea047be3042dba34cf8685`, 32,045 bytes: the repo-root canonical copy and one under `src/main/resources/seed/` in each of `insight-service`, `market-data-service`, and `portfolio-service`. Earlier analysis recorded that nothing enforces the copies stay in sync; that is **incorrect and is corrected here**. Each of the three services registers a `copySeedTickers` Copy task wired as `dependsOn` of `processResources` (e.g. `portfolio-service/build.gradle:15–24`), so any build re-synchronises them. The actual defect is different and worse: the task copies into **git-tracked** source directories, so generated content is committed, git state and build state can disagree, and a direct edit to a service copy is silently reverted by the next build. A CI check asserting the copies match would enforce a redundancy that should not exist rather than removing it.

Three separate loaders already read that file. `TickerCatalogService` in `insight-service` loads it at startup, runs integrity checks that throw on blank names, null alias lists, or duplicate tickers, builds ticker and alias indexes, filters by asset class, computes a SHA-256 catalog version for cache keys, and normalizes user tokens (`BTC` → `BTC-USD`, `USDCHF` → `USDCHF=X`). `SeedTickerRegistry` exists twice — in `market-data-service` and in `portfolio-service` — with identical public surfaces (`all()`, `find()`, a `SeedTicker` record). So the service that must validate writes already has the catalog on its classpath and in memory; validation requires no cross-service call and incurs no cold-start penalty.

Finally, the freshness signal this spec needs does not currently exist in usable form. `MarketPriceProjectionService.upsertLatestPrice` writes `updated_at = now()` — the *consumer's* receive time, discarding the producer's `observedAt` entirely — and its `ON CONFLICT DO UPDATE` carries `WHERE market_prices.current_price IS DISTINCT FROM EXCLUDED.current_price OR market_prices.quote_currency IS DISTINCT FROM EXCLUDED.quote_currency`. The row is therefore **not touched at all** when a price is observed successfully but is numerically unchanged. A stable quote polled correctly every day for a month would present as a month old, while a delayed event would present as fresh. Requirement 9 establishes an authoritative observation timestamp rather than building on that field. `PriceUpdatedEvent` already carries an `observedAt`, which the history path consumes and the latest-price projection discards.

Scope is deliberately limited to the invariant, the data, and the valuation contract. This spec delivers no frontend change and no interactive feature. `GET /api/assets`, the composition API, optimistic concurrency, the picker UI, and the demo reset and presence behaviour are specified separately in `asset-picker-composition`, which depends on this one.

## Glossary

- **Supported_Catalog**: The declared set of assets this product supports. The single authority for what may be held, what is refreshed, and what a user may select. Replaces the Emergent_Tracked_Set as the definition of product capability.
- **Canonical_Manifest**: The single tracked file that defines the Supported_Catalog. Today `config/seed-tickers.json`; it remains the one version-controlled copy.
- **Catalog_Module**: A shared build module owning parsing, integrity validation, indexing, and version computation for the Canonical_Manifest, consumed by every service that needs it. Replaces the three independent loaders.
- **Catalog_Version**: A deterministic content-derived identifier for a loaded Supported_Catalog, covering every behaviourally relevant field rather than ticker membership alone — **including `basePrice`**, even though `basePrice` reaches consumers only through the Seed_Only_Interface. It changes deterministic seeded cost bases, so it changes behaviour, and a version that did not move when it changed would misreport two builds as identical.
- **Lifecycle_Status**: A per-entry state in the Canonical_Manifest. `ACTIVE` — supported, refreshed, selectable. `DEPRECATED` — retained for identity and history, not refreshed, not selectable for new holdings.
- **Active_Asset** / **Deprecated_Asset**: A Supported_Catalog entry whose Lifecycle_Status is `ACTIVE` / `DEPRECATED`.
- **Referential_Invariant**: Every persisted holding resolves to some Supported_Catalog entry, active or deprecated.
- **New_Write_Invariant**: Every newly created or replaced holding names an Active_Asset.
- **Refresh_Invariant**: The Refresh_Desired_Set equals the Active_Asset set.
- **Deprecated_Position**: An existing holding naming a Deprecated_Asset. Remains visible and valued at its last known price; is not refreshed; is migration-pending.
- **Refresh_Desired_Set**: The set of symbols the market-data refresh is required to fetch.
- **Emergent_Tracked_Set**: The pre-existing behaviour of `resolveTrackedTickers()` — configured baseline union all tickers present in Mongo. Retired by this spec.
- **Write_Boundary**: Every code path that persists an `asset_holdings` row — the existing `POST /api/portfolio/{portfolioId}/holdings`, the Golden_State_Seeder, Flyway migrations, and any future composition endpoint. The **invariant** is uniform across all of them; the **enforcement mechanism** is not, because these paths have different capabilities. See Application_Operation, Http_Entry_Point, Direct_Caller, Migration_Write_Path, and the Layer_Composition_Rule.
- **Http_Entry_Point**: A controller exposing an Application_Operation over HTTP — `POST /api/portfolio/{portfolioId}/holdings`, the internal seed endpoint, and later the composition endpoint. It adds no validation of its own; it **maps** the typed failure raised beneath it to the defined 422 contract.
- **Direct_Caller**: Any non-HTTP invoker of an Application_Operation — a test, a scheduled task, another service-internal component. It observes the typed failure directly, with no HTTP semantics involved.
- **Layer_Composition_Rule**: Application_Operation and Http_Entry_Point are **layers, not alternatives**. The Golden_State_Seeder is both: `PortfolioSeedController` receives HTTP and invokes the seeder directly, so the seeder is simultaneously an Application_Operation and, in production, reached through an Http_Entry_Point. An earlier draft defined them as mutually exclusive and described the seeder as having 'no request in flight', which is false for its production invocation.
- **Application_Operation**: An in-process writer that persists holdings — the holding endpoint's service layer, the Golden_State_Seeder, and any future composition service. It validates against the Catalog_Module, and signals a rejection as a **typed failure with atomic rollback**. This is the layer where the invariant is actually enforced, and it is enforced identically regardless of who called it.
- **Migration_Write_Path**: A Flyway migration. It runs before and outside the application context, so it can neither load the Catalog_Module nor return a status code. It satisfies the invariant **by construction** — writing only values the migration itself fixes — and is checked afterwards by the Post_Migration_Integrity_Assertion.
- **Post_Migration_Integrity_Assertion**: A check run after migrations complete, asserting **both** that holdings created or replaced by the migration satisfy the New_Write_Invariant and that the whole store satisfies the Referential_Invariant. Checking only the latter would be too weak — it permits deprecated assets, so a migration could create a brand-new Deprecated_Position and still pass. It is a gate, not a diagnostic: see Requirement 6.
- **Unsupported_Asset_Rejection**: The refusal of a Write_Boundary operation naming a ticker that is not an Active_Asset, without partial mutation.
- **Global_Price_Table**: The `market_prices` table. Keyed by ticker, not scoped by user; every reader and writer shares one row per symbol.
- **Observation_Timestamp**: The **producer processing time** — assigned as `Instant.now()` per ticker inside the refresh loop, after the batch fetch of all quotes has succeeded and before that ticker's price is persisted and its event published, then carried by `PriceUpdatedEvent.observedAt`. It is deliberately not described as the instant of that ticker's own fetch: quotes are retrieved as one batch and stamped individually afterwards, so the timestamp trails the actual retrieval by the duration of the batch and the loop position. It is **not** provider-reported: the Yahoo response DTO carries only `symbol` and `regularMarketPrice`, so no upstream market timestamp exists to propagate. The authoritative input to Asset_Price_Freshness. Distinct from Receive_Time, which is when the *consumer* processed the event.
- **Receive_Time**: The time this system processed a price event. Currently written to `market_prices.updated_at`. Not a freshness signal.
- **Asset_Price_Freshness**: Metadata describing how current the **asset prices** underlying a valuation are, reported independently of Partial_Valuation. Deliberately scoped: it does **not** describe the age of FX rates, which are a separate multiplicand in the same total. The name is narrow on purpose — see Requirements 9.46 to 9.49 and D19.
- **Fx_Rate_Age**: The age of the FX rate applied to a non-base-currency holding. Out of scope here, named so the exclusion is explicit rather than an omission.
- **Freshness_State**: The reported state of a valuation input. `FRESH` — an Observation_Timestamp within the Freshness_Threshold. `STALE` — an Observation_Timestamp older than it. `UNKNOWN` — a price row exists but carries no Observation_Timestamp, so its age cannot be determined. `MISSING` — no price row exists for the holding at all.
- **Freshness_Precedence**: The severity order used to reduce per-holding Freshness_States to one portfolio-level state: `MISSING` > `UNKNOWN` > `STALE` > `FRESH`. `UNKNOWN` outranks `STALE` deliberately — a stale price's age is bounded and reportable, so a reader can judge it, whereas an unknown one's age is unbounded and could be arbitrarily worse.
- **Postgres_Repair**: The `asset_holdings`, `market_prices`, and `market_price_history` corrections, executed as Flyway migrations and idempotent under Flyway re-execution.
- **Mongo_Repair**: The corresponding correction to `market-data-service`'s Mongo state. Flyway does not manage Mongo, so this is a separate mechanism with its own idempotency guarantee and its own completion evidence.
- **Stale_Holding**: A holding whose backing Observation_Timestamp is older than the Freshness_Threshold.
- **Missing_Price_Holding**: A holding with no resolvable row in the Global_Price_Table. Distinct from stale; excluded from `totalValue` and reported via Partial_Valuation.
- **Unknown_Price_Holding**: A holding whose price row exists but carries no Observation_Timestamp, so its age cannot be determined. Included in `totalValue` at its last known price, like a Stale_Holding and unlike a Missing_Price_Holding, and counted separately in the freshness response.
- **Freshness_Threshold**: The age beyond which a price is reported stale, expressed as a number of missed scheduled refresh cycles plus a grace period, so that the normal ~24h price age never reports stale.
- **Refresh_Cycle**: The interval between scheduled production refreshes — 24 hours, from the ACA Job's `0 8 * * *`.
- **Partial_Valuation**: The existing `partialValuation` flag, meaning a holding was **excluded** from `totalValue`. Its meaning is unchanged and is not extended to cover staleness.
- **Seed_Only_Interface**: A narrowly scoped interface exposing `basePrice` to the Golden_State_Seeder alone, so that deterministic seeded cost bases remain reproducible without `basePrice` reaching general catalog, validation, asset-API, or valuation code.
- **Golden_State_Seeder**: `PortfolioSeedService`, which wipes and re-seeds a user's portfolio with the catalog. Reachable in production and invoked there daily; as of PR #97 it writes holdings only — see Requirement 11.
- **Holdings_Only_Seed**: A seed path that writes portfolios and holdings and performs no write to the Global_Price_Table or `market_price_history`.
- **Demo_Portfolio**: The portfolio owned by `demo@wealthtracker.dev` (`00000000-0000-0000-0000-0000000d3110`), reached by the credentials pre-filled on the login page. Currently 3 holdings.
- **E2E_Portfolio**: The portfolio owned by `00000000-0000-0000-0000-000000000e2e`, currently 160 holdings, wiped and re-seeded by the Golden_State_Seeder on every run.
- **Symbol_Correction**: Replacing a ticker that is factually wrong with the correct one for the same instrument. `MM.NS` → `M&M.NS`.
- **Corporate_Action_Migration**: Resolving a ticker whose underlying instrument has changed identity, requiring an allocation decision rather than a substitution. `TATAMOTORS.NS` → `TMCV.NS` and/or `TMPV.NS`.
- **Catalog_Load_Failure**: The condition where the Canonical_Manifest is absent, unreadable, unparseable, fails integrity validation, or contains no Active_Asset.

## Requirements

### Requirement 1: One canonical manifest, packaged without mutating tracked sources

**User Story:** As a maintainer, I want exactly one version-controlled copy of the asset list, so that a change to it cannot be partially applied and cannot be silently reverted by a build.

#### Acceptance Criteria

1. THE repository SHALL contain exactly one tracked Canonical_Manifest.
2. THE build SHALL make the Canonical_Manifest available on the runtime classpath of every consuming service without writing into any git-tracked directory.
3. THE three tracked copies under `src/main/resources/seed/` SHALL be deleted from version control.
4. THE build SHALL NOT retain a `copySeedTickers` task, or any successor, whose output destination is a tracked source directory.
5. WHEN a developer edits the Canonical_Manifest and builds, THE change SHALL take effect in every consuming service with no further manual step.
6. THE increment SHALL NOT introduce a CI check asserting that duplicate manifest copies match, because the duplication itself is removed rather than policed.

### Requirement 2: A single shared catalog module

**User Story:** As a maintainer, I want one implementation of catalog loading and validation, so that three services cannot disagree about what the catalog contains or whether it is valid.

#### Acceptance Criteria

1. THE Catalog_Module SHALL own parsing, integrity validation, indexing, and Catalog_Version computation for the Supported_Catalog.
2. THE Catalog_Module SHALL expose, at minimum: the full entry set, lookup by canonical ticker, filtering by asset class, filtering by Lifecycle_Status, and the Catalog_Version.
3. THE `SeedTickerRegistry` implementations in `market-data-service` and `portfolio-service` SHALL be replaced by the Catalog_Module.
4. THE integrity validation SHALL reject: blank names; null alias lists; duplicate canonical tickers; entries whose Lifecycle_Status is absent or unrecognised; **blank canonical tickers; blank asset classes; blank quote currencies; and a `basePrice` that is null, zero, or negative**. The added checks exist because the refresh derives its desired set from the ticker, the seeder derives cost bases from `basePrice`, and valuation derives FX conversion from the quote currency — an entry missing any of them loads cleanly today and fails at the point of use.
5. THE Catalog_Module SHALL NOT absorb `insight-service`'s alias normalization or LLM grounding view, which remain in `insight-service` because they serve natural-language resolution rather than catalog identity.
6. THE Catalog_Version SHALL be identical across all services deployed from one commit, and SHALL be observable at runtime for verification.
7. THE Catalog_Version SHALL be a deterministic hash over every behaviourally relevant field — canonical ticker, name, aliases, asset class, quote currency, Lifecycle_Status, **and `basePrice`** — and SHALL NOT be computed from ticker membership alone, because a Lifecycle_Status change alters system behaviour without altering the ticker set.
8. THE inclusion of `basePrice` in 2.7 SHALL hold even though `basePrice` is reachable only through the Seed_Only_Interface, because it determines seeded cost bases: a Catalog_Version that did not move when `basePrice` changed would report two behaviourally different builds as identical.
9. THE Catalog_Version SHALL be wider than the current 8 hexadecimal characters.
10. THE Catalog_Module SHALL NOT expose `basePrice` through its general catalog, validation, asset-API, or valuation interfaces.
11. THE Catalog_Module MAY expose `basePrice` through a Seed_Only_Interface consumed exclusively by the Golden_State_Seeder, so that deterministic seeded cost bases remain reproducible.

### Requirement 3: Lifecycle status on every catalog entry

**User Story:** As a maintainer, I want the catalog to express that an asset is retired without deleting it, so that a symbol whose instrument has changed can stop being offered without orphaning the holdings that reference it.

#### Acceptance Criteria

1. EACH Canonical_Manifest entry SHALL carry an explicit Lifecycle_Status.
2. THE permitted values SHALL be `ACTIVE` and `DEPRECATED`.
3. THE Supported_Catalog SHALL contain at least one Active_Asset in every supported asset class.
4. THE integrity validation SHALL NOT assert a fixed total entry count, nor fixed per-class counts, because corporate actions cause the supported universe to change over time.
5. A Deprecated_Asset SHALL remain resolvable by ticker lookup, so existing holdings and historical rows retain a valid referent.
6. THE Lifecycle_Status of an entry SHALL NOT be inferred from provider behaviour at runtime; it is a declared property of the Canonical_Manifest.
7. THE Lifecycle_Status field SHALL be added to **every** Canonical_Manifest entry in the same change that introduces the validation requiring it. No `lifecycleStatus` field exists on any of the 160 entries today, so shipping the validation first would make the catalog fail its own integrity check.
8. THERE SHALL be no deployable state in which the Catalog_Module rejects entries for a missing Lifecycle_Status while the Canonical_Manifest still lacks it. Combined with Requirement 10, that state would fail startup in **every** catalog-consuming service simultaneously — a total outage produced by the safety mechanism rather than prevented by it.

### Requirement 4: Symbol corrections and deprecations, applied before catalog authority

**User Story:** As the operator, I want known-broken symbols fixed before the catalog becomes the declared source of truth, so that promoting the catalog does not promote two broken symbols into declared product capability.

#### Acceptance Criteria

1. THE Canonical_Manifest SHALL apply the Symbol_Correction `MM.NS` → `M&M.NS`. This is a Symbol_Correction rather than a Corporate_Action_Migration: the instrument is unchanged and only the symbol was wrong, so no allocation decision arises.
2. THE market-data client SHALL be verified to URL-encode `&` correctly in a request for `M&M.NS`, because the most likely cause of the original mangling is encoding avoidance.
3. THE Canonical_Manifest SHALL mark `TATAMOTORS.NS` as a Deprecated_Asset.
4. THE increment SHALL NOT silently substitute a single successor for `TATAMOTORS.NS`, because a demerger splits one instrument into two and requires an allocation rule.
5. THE Corporate_Action_Migration for `TATAMOTORS.NS` SHALL be recorded as an explicit open decision rather than resolved by default.
6. THE increment SHALL make no change to `USDINR=X`, which was verified transient.
7. THERE SHALL be no deployable intermediate state in which the Refresh_Desired_Set is derived from the Supported_Catalog while `MM.NS` or `TATAMOTORS.NS` remain Active_Assets.
8. THE manifest change in 4.1 SHALL be accompanied by the data migration specified in Requirement 7, because renaming a catalog entry without migrating the rows that reference it would create Deprecated_Positions by accident.

### Requirement 5: The refresh set is derived from the catalog

**User Story:** As the operator, I want the refresh job to fetch exactly what the product declares it supports, so that what is refreshable stops depending on what happened to be seeded in the past.

#### Acceptance Criteria

1. THE Refresh_Invariant SHALL hold: the Refresh_Desired_Set equals the Active_Asset set.
2. THE Emergent_Tracked_Set behaviour — union with all tickers present in Mongo — SHALL be retired.
3. THE refresh SHALL NOT fetch a Deprecated_Asset.
4. THE refresh SHALL retain its existing skip-and-keep-last-price behaviour on a provider miss, and SHALL NOT throw.
5. THE refresh cadence, the ACA Job as sole production refresh path, and the disabled in-service scheduler SHALL remain unchanged.
6. WHEN the Supported_Catalog contains a symbol absent from the Global_Price_Table, THE refresh SHALL treat it as a normal fetch candidate rather than an error.
7. THE increment SHALL NOT delete Global_Price_Table rows solely because their ticker is a Deprecated_Asset, because valuation of existing Deprecated_Positions depends on them.

### Requirement 6: Validation at the portfolio write boundary

**User Story:** As a user, I want the system to refuse to record a holding it cannot price, so that my portfolio total is never silently built on a price that will never update.

#### Acceptance Criteria

1. THE New_Write_Invariant SHALL hold at every Write_Boundary: a newly created or replaced holding names an Active_Asset.
2. THE existing `POST /api/portfolio/{portfolioId}/holdings` SHALL be subject to this validation, not only future endpoints.
3. AN Unsupported_Asset_Rejection SHALL leave no partial mutation: no holding row, no cost-basis write, no portfolio-level side effect.
4. EVERY Http_Entry_Point exposing an Application_Operation — including the internal seed endpoint — SHALL map an Unsupported_Asset_Rejection to **HTTP 422** with a machine-readable body carrying `error: "unsupported_asset"`, the rejected ticker, and the Catalog_Version against which it was rejected.
5. THE validation SHALL match on canonical ticker only, and SHALL NOT accept aliases or perform normalization on the financial write path, because a near-miss silently resolving to a different instrument is worse than a rejection.
6. THE validation SHALL execute in the Application_Operation, in-process in `portfolio-service`, using the Catalog_Module, and SHALL NOT introduce a synchronous cross-service call on the write path. An Http_Entry_Point SHALL NOT re-implement it.
7. THE Golden_State_Seeder SHALL satisfy the New_Write_Invariant, and SHALL therefore seed from Active_Assets only.
8. THE New_Write_Invariant SHALL NOT be applied retroactively to Deprecated_Positions: an existing holding whose asset has since been deprecated SHALL continue to be valued and displayed, and SHALL NOT be deleted or blocked from removal.
9. A request that reduces or removes a Deprecated_Position SHALL be permitted; a request that creates or increases one SHALL be rejected per 6.1.
10. AN Application_Operation SHALL signal an Unsupported_Asset_Rejection as a **typed failure** with atomic rollback. A Direct_Caller observes that failure without HTTP semantics; an Http_Entry_Point maps it per 6.4. The same operation SHALL behave identically either way — the caller determines only how the failure is *reported*, never whether it is *detected*.
11. A Migration_Write_Path SHALL satisfy the New_Write_Invariant **by construction**, writing only ticker values the migration itself determines, and SHALL NOT be required to load the Catalog_Module or return a status code — it runs before and outside the application context and can do neither.
12. THE Post_Migration_Integrity_Assertion SHALL check **both** invariants: that every holding the migration created or replaced names an Active_Asset, and that the store as a whole satisfies the Referential_Invariant.
13. PRE-EXISTING Deprecated_Positions retained under 7.28 SHALL be exempt from the first check. The distinction is between a position the migration *created* and one it merely *left alone*.
14. CHECKING only the Referential_Invariant would be insufficient, because it admits deprecated assets: a migration could create a brand-new Deprecated_Position and pass its own enforcement check.
15. A failed Post_Migration_Integrity_Assertion SHALL block startup or deployment **before** write paths are enabled. It SHALL NOT be advisory or diagnostic-only — it is the sole enforcement mechanism for a path that cannot validate inline.
16. EVERY Migration_Write_Path SHALL be followed by the Post_Migration_Integrity_Assertion. That assertion is the enforcement mechanism for migrations, replacing the inline validation they cannot perform.
17. THE invariant in 6.1 is uniform across every writer; only the **mechanism** differs. Two earlier drafts got this wrong in opposite directions: one required HTTP 422 and in-process Catalog_Module validation of *every* Write_Boundary while defining that term to include Flyway migrations, making those criteria unsatisfiable; its replacement modelled HTTP and application writers as mutually exclusive, which is false for the seeder — `PortfolioSeedController` invokes it over HTTP in production.

### Requirement 7: Repair of existing holdings and orphaned prices

**User Story:** As the operator, I want existing rows that violate the invariant removed from production data, so that enforcing the invariant does not leave data that breaks it.

#### Acceptance Criteria

1. THE existing `BTC` holding SHALL be migrated to `BTC-USD` by the Postgres_Repair, preserving quantity.
2. THE orphaned `BTC` row in the Global_Price_Table SHALL be removed, so that no reader can resolve a price for it again.
3. EXISTING `asset_holdings`, Global_Price_Table, and `market_price_history` rows referencing `MM.NS` SHALL be migrated to `M&M.NS` by the Postgres_Repair.
4. ANY `market-data-service` Mongo state referencing `MM.NS` SHALL be migrated to `M&M.NS` by the Mongo_Repair. This SHALL NOT be specified as a Flyway migration: Flyway manages the Postgres schema only, so a requirement placing Mongo state under "idempotent under Flyway re-execution" would be unsatisfiable.
5. THE Mongo_Repair SHALL be idempotent by its own mechanism, and SHALL record completion evidence independently of the Postgres_Repair.
6. THE deployment ordering between the Postgres_Repair and the Mongo_Repair SHALL be explicit, and SHALL be safe to interrupt between them — neither may leave a state in which one store enforces a symbol the other cannot resolve.
7. THE **holding** migrations in 7.1 and 7.3 SHALL preserve quantity and cost-basis fields, or explicitly recompute them, and SHALL record which was done. This clause SHALL NOT be applied to price-state repairs: neither `market_prices`, `market_price_history`, nor the Mongo price documents carry a quantity or cost basis, so requiring their preservation there would be unsatisfiable.
8. PRICE-state preservation SHALL be specified **per store shape**, because the three stores do not hold the same fields and a single blanket rule is unsatisfiable for at least one of them:
   a. `market_prices` (Postgres) — preserve `current_price`, `quote_currency`, the Observation_Timestamp, and `updated_at` (Receive_Time). It has no reference-metadata columns, so requiring their preservation here would be impossible to satisfy.
   b. `market_price_history` (Postgres) — preserve `price`, `quote_currency`, `observed_at`, and continuity of the series for the migrated symbol.
   c. Mongo `market_prices` — preserve the five-field tuple defined in 7.16, carried over whole.
9. PRESERVATION SHALL NOT be required of rows the repair deliberately **removes**. The orphaned `BTC` current-price row (7.2) and the losing side of any collision are recorded and discarded, not preserved — an earlier draft demanded both removal and preservation of the same row. Their corresponding **historical observations** in `market_price_history` remain preserved regardless — because history is an append-only record of what was observed and is not invalidated by a current-state correction — **except** for fabricated history under 7.18 to 7.20, which was never observed and is archived rather than retained. That exception is the only case where a repair removes history.
10. WHERE a portfolio holds both the source and destination symbol of a migration — including a portfolio already holding both `BTC` and `BTC-USD` — THE migration SHALL define and apply an explicit collision rule, either combining quantities or refusing the migration for that portfolio and reporting the conflict, rather than violating the `UNIQUE (portfolio_id, asset_ticker)` constraint.
11. WHERE both source and destination symbols already exist in `market_prices` (whose `ticker` is the primary key), THE repair SHALL retain the row with the newer Observation_Timestamp. A known timestamp SHALL win over a null one. WHERE both are null, the repair SHALL retain the destination row and SHALL record the discarded source row, so the outcome is deterministic rather than dependent on scan order.
12. WHERE both rows carry the **same known** Observation_Timestamp, THE repair SHALL apply the distinction drawn in Requirements 9.15 and 9.16 rather than picking arbitrarily: identical payloads collapse idempotently, and conflicting payloads are surfaced for operator resolution rather than resolved by guess. Two different prices claiming one observation instant is the same upstream fault at repair time as it is at projection time, and it is more dangerous here because a migration is a one-shot action.
13. WHERE both symbols carry `market_price_history` rows at the same `observed_at` — the table's uniqueness key is `(ticker, observed_at)` — identical observations SHALL collapse idempotently, and **conflicting** payloads at one timestamp SHALL be surfaced rather than silently overwritten, for the same reason given in Requirement 9.16.
14. WHERE both symbols exist in `market-data-service`'s Mongo `market_prices` collection, whose `ticker` is the `@Id`, THE Mongo_Repair SHALL order candidate documents by **`updatedAt`** and retain the newer one under the destination key. A document with a known `updatedAt` SHALL win over one with a null `updatedAt`; where both are null, the destination document SHALL be retained and the discarded one recorded.
15. WHERE both documents carry the same `updatedAt` and identical field values, THE repair SHALL collapse them idempotently; where they carry the same `updatedAt` but conflicting values, THE repair SHALL surface the conflict rather than choose.
16. "Complete document" in 7.14 means the full tuple `currentPrice`, `quoteCurrency`, `updatedAt`, `previousReferencePrice`, `previousReferenceAt` — carried over together or not at all. A document SHALL NOT be assembled field-by-field from two sources, because mixing a price from one observation with reference fields from another produces change figures that describe no real interval.
17. THE migrations SHALL preserve `market_price_history` continuity for the migrated symbol rather than orphaning prior observations, **except** for fabricated history as defined in 7.18.
18. THE legacy `BTC` `market_price_history` rows are **fabricated**: synthetic values seeded by `V2__Seed_Market_Data.sql` for a symbol this system never tracked against a provider. They SHALL NOT be migrated into `BTC-USD`, because merging invented observations into a series of real ones would corrupt every change figure computed across that window. Canonical continuity does not apply to data that was never observed.
19. THOSE rows SHALL NOT remain in operational history either. They SHALL be copied **verbatim** into a repair archive with reason `LEGACY_SYNTHETIC`, and then removed from `market_price_history`. Retention in the operational table would leave fabricated observations queryable by every consumer of that table, which is the same defect as merging them, differing only in which ticker carries it.
20. THE archive copy SHALL be exact — every column, unaltered — so the removal is reversible and auditable. This is the one place where "preserve" means archival rather than continuity.
21. **NO non-repair writer SHALL mutate the source or destination documents while the Mongo_Repair is in progress.** Each such writer SHALL either be quiesced for the duration, or participate in the repair fence, until the repair reaches terminal success. This is the invariant; the criteria below name the mechanisms that satisfy it for the writers that exist today, and any writer added later SHALL be assessed against this criterion rather than against the list.
22. THE production refresh Job's **write capability** SHALL be disabled for the duration of the Mongo_Repair, and no execution of it SHALL be performing a refresh when the repair begins. The requirement is that the Job cannot mutate Mongo — **not** that its trigger stops firing. Suspending the trigger is one way to achieve that and is not the chosen one, because in the pinned AzureRM provider the schedule block is `ForceNew` and editing it would replace the Job.
23. WHILE the write capability is disabled, a scheduled execution SHALL still start, perform no write, emit an observable suspended-mode signal, and **terminate successfully**. A disabled execution that hangs until its replica timeout is not an acceptable no-op: it produces a failed execution and no signal.
24. THE write capability SHALL be re-enabled only after the Mongo_Repair reaches its terminal success state, and the first refreshing execution afterwards SHALL be a single controlled run that is verified before the persisted configuration is changed back.
25. GATEWAY ingress suspension SHALL NOT be relied upon to stop the refresh Job. The Job is a separate `azurerm_container_app_job` that does not traverse the gateway, so closing ingress leaves it firing on its cron.
26. `MM.NS` history SHALL migrate normally under 7.17. Its observations are real, its instrument is unchanged, and only its symbol was wrong.
27. THE migrations SHALL remove obsolete current-state rows for the source symbol once the destination symbol carries the position.
28. ANY persisted holding naming `TATAMOTORS.NS` SHALL be retained as a Deprecated_Position, not deleted and not reassigned, pending the Corporate_Action_Migration decision.
29. THE Postgres_Repair SHALL be idempotent under Flyway re-execution.
30. A verification SHALL assert, against the live database, that the Referential_Invariant holds: every persisted holding resolves to a Supported_Catalog entry.
31. THE enforcement in Requirement 6 SHALL NOT be activated before the Postgres_Repair and the Mongo_Repair have both completed.

### Requirement 8: An independent, deterministic demo portfolio

**User Story:** As someone demonstrating this application, I want the demo account to hold a populated portfolio that CI cannot destroy, so that the showcase is reliable.

#### Acceptance Criteria

1. THE Demo_Portfolio SHALL be independent of the E2E_Portfolio; the two SHALL NOT share a portfolio row.
2. THE Demo_Portfolio SHALL be reproducible deterministically from the Supported_Catalog.
3. THE Demo_Portfolio SHALL hold the full Active_Asset set.
4. EVERY holding in the Demo_Portfolio SHALL reference an Active_Asset by construction rather than by review.
5. A Golden_State_Seeder run against the E2E user SHALL NOT alter the Demo_Portfolio.
6. THE Demo_Portfolio SHALL be seeded via the Holdings_Only_Seed path.
7. THE increment SHALL NOT seed real self-service users with any holdings; new users continue to start empty.
8. LIVE and synthetic contract checks SHALL assert the seeded holding count **equal to the cardinality of the Active_Asset set**, not greater-than-or-equal to a literal. `api-live-smoke.spec.ts` currently asserts `holdingsInserted >= 160`, which contradicts Requirement 3.4 and would pass silently if the seeder over-seeded, and fail as soon as `TATAMOTORS.NS` is deprecated without a successor being added.
9. THE Catalog_Version SHALL be asserted **separately**, as an identity check that every deployed service agrees on one catalog. It is a hash and carries no cardinality information, so it cannot substitute for the count assertion in 8.8.
10. NO test, monitor, or verification step that exercises the **canonical or live catalog** SHALL encode that catalog's size as a literal. This prohibition SHALL NOT extend to tests using a deliberately constructed fixture catalog, which must be able to assert their own known size — a two-entry fixture asserting `2` is correct, not a violation.

### Requirement 9: Asset-price freshness, on an authoritative observation timestamp

**User Story:** As a user, I want to be told when my total is computed from old prices, so that a stale valuation is visible rather than silent.

#### Acceptance Criteria

1. THE Global_Price_Table SHALL persist the Observation_Timestamp of the price it holds.
2. THE latest-price projection SHALL treat price, quote currency, and Observation_Timestamp as **one tuple**, updating all three together or none of them. A partial update is what allows an old price to sit under a new timestamp and read as fresh.
3. THE incoming quote currency SHALL be **normalized before** any tuple comparison or write. `PriceUpdatedEvent.quoteCurrency` is nullable for old-shape events while `market_prices.quote_currency` is `NOT NULL`, so an unnormalized null would otherwise participate in equality and conflict detection as an unspecified value.
4. WHERE the incoming quote currency is null, THE projection SHALL resolve it from the Supported_Catalog entry for that ticker.
5. WHERE it cannot be resolved from the Supported_Catalog, THE projection SHALL reject the event and surface it. It SHALL NOT fall back to a default. Defaulting to `USD` would silently mis-denominate every `.NS` and `=X` instrument, converting a data-quality fault into a wrong valuation that reads as correct — the same class of silent error this spec exists to remove.
6. WHERE an event's ticker is **absent from the Supported_Catalog entirely**, THE projection SHALL reject and surface it, independent of whether its quote currency is null or non-null. This is the general rule; 9.5 and 9.12 are the currency-specific cases beneath it. Without it, an out-of-catalog event carrying a non-null currency falls through undefined — 9.5 covers only a failed null-currency resolution, and 9.12 presumes a catalog entry exists to compare against.
7. THE rejection rules in 9.5, 9.6, and 9.12 SHALL NOT be activated except under the explicit waiver in 9.9, and otherwise not until **all** of the following hold. Deploying Requirement 5 first is necessary but **not sufficient**: it stops the refresh *producing* out-of-catalog events, while events already published remain in Kafka and would be rejected on consumption.
   a. Requirement 5 has narrowed the Refresh_Desired_Set to Active_Assets, so no new out-of-catalog events are produced.
   b. Requirement 7's repairs have completed, so no ticker awaiting migration is still in flight.
   c. The pre-cutover Kafka backlog has drained or expired.
8. BACKLOG drainage SHALL be **established by evidence** — consumer lag at zero, or the topic retention window fully elapsed since 9.7a — and SHALL NOT be assumed from elapsed deployment time. Lag-zero evidence SHALL be observed **after every producer revision capable of emitting the emergent set has stopped**, and **across every partition** of the projection's consumer group. A momentary zero observed while such a producer is still running can precede a final in-flight publication, and a single lagging partition is enough to carry one.
9. THE waiver: the operator MAY activate the rejection rules before 9.7c holds, provided the discarded events are counted and surfaced and the acceptance is recorded as a deliberate decision. Silent discard is not an available option. This is the sole exception permitted by 9.7; 9.7a and 9.7b are **not** waivable, because activating before the producer is narrowed or the repairs have landed would reject events the system is still expected to produce.
10. THIS constraint is stronger than the analogous one in 7.31, which governs a synchronous write path where no backlog can exist. An asynchronous consumer has queued work that predates any deployment gate, so ordering deployments alone cannot protect it.
11. NORMALIZATION SHALL NOT alter a non-null incoming quote currency, but SHALL NOT accept it unchecked either: after normalization THE quote currency SHALL equal the Supported_Catalog's `quoteCurrency` for that ticker.
12. WHERE a non-null incoming quote currency contradicts the catalog, THE projection SHALL reject the event and surface the mismatch. It SHALL NOT persist the incoming value. A currency change is a change to what the instrument *is*, and must enter through the Canonical_Manifest — the authority this spec establishes — rather than by an event quietly redenominating a ticker the catalog describes differently.
13. THE tuple SHALL be written only when the incoming observation's Observation_Timestamp is strictly newer than the stored one — **including when the numeric price and quote currency are unchanged**, because a successful unchanged observation is evidence of freshness.
14. WHEN an incoming observation carries an Observation_Timestamp older than the stored one, THE projection SHALL write nothing at all: not the price, not the currency, not the timestamp.
15. WHEN an incoming observation carries an Observation_Timestamp equal to the stored one and an identical payload, THE projection SHALL be a no-op, so redelivery is idempotent.
16. WHEN an incoming observation carries an Observation_Timestamp equal to the stored one but a **conflicting** payload, THE projection SHALL reject the write and surface the conflict, because two different prices claiming the same observation instant indicate an upstream fault that silent last-write-wins would hide.
17. "Strictly newer" in 9.13 is undefined where either timestamp is absent, and absent timestamps are the **normal** case on arrival: every existing `market_prices` row acquires a null Observation_Timestamp when the column is added, and `PriceUpdatedEvent.observedAt` is explicitly nullable for old-shape events. The four transitions SHALL therefore be specified as follows.
18. WHEN the incoming Observation_Timestamp is known and the stored one is null, THE tuple SHALL be written, because a known timestamp is strictly more informative than none and this is the path by which legacy rows acquire provenance.
19. WHEN the incoming Observation_Timestamp is null and the stored one is known, THE projection SHALL write nothing at all. **An old-shape event SHALL NEVER overwrite a tuple whose timestamp is known** — doing so would silently downgrade a dated price to an undatable one, converting a `FRESH` or `STALE` holding into `UNKNOWN` and destroying the evidence needed to tell them apart.
20. WHEN both the incoming and stored Observation_Timestamps are null, THE projection SHALL write the price and quote currency and SHALL leave the timestamp null. This is not optional: an earlier draft said `MAY`, which left implementation and tests unable to derive one expected result.
21. WHERE two null-timestamp payloads conflict, THE later-received one SHALL win. Receive_Time is the only ordering available when no observation time exists on either side, so this is last-write-wins by necessity rather than by preference. It is an accepted limitation, bounded by the fact that such a row always reports `UNKNOWN` and so never claims an age it cannot support.
22. THE projection SHALL emit an observable signal when it takes the null-over-null path, so that a producer emitting undated events is detectable rather than silently degrading every row it touches to `UNKNOWN`.
23. THE **runtime history writer** SHALL apply the same identical-versus-conflicting distinction as the latest-price tuple. An insert whose `(ticker, observed_at)` already exists with an identical payload SHALL be an idempotent no-op; the same key with a **conflicting** payload SHALL be surfaced.
24. THE current `ON CONFLICT (ticker, observed_at) DO NOTHING` behaviour SHALL NOT be retained unqualified, because it cannot distinguish those two cases: a genuine redelivery and a contradictory observation at the same instant are both silently discarded. The latest-row rules do not cover this, since an out-of-order observation can conflict with an **older history row** without its timestamp equalling the current row's.
25. THE latest-row write and the history append for one dated event SHALL occur in **one transaction**. A surfaced history conflict SHALL leave **both** tables unchanged.
26. WITHOUT 9.25 the two writes could diverge: the current price would advance while its corresponding history row was rejected, leaving a series whose latest point has no observation behind it — a self-inflicted instance of the price-without-provenance problem this requirement exists to prevent. This preserves the existing `@Transactional` boundary on `MarketPriceProjectionService.upsertLatestPrice` rather than widening it.
27. WHEN an event carries no Observation_Timestamp, THE projection SHALL process the latest row under the null-timestamp rules above, SHALL append **no** `market_price_history` row, and SHALL NOT substitute Receive_Time for the missing observation time. The observable undated-event signal SHALL still be emitted.
28. THE prohibition in 9.27 is explicit because both "skip history" and "invent an identity timestamp" would otherwise be conforming readings, and the second would fabricate provenance — writing a history point asserting an observation that never happened.
29. ON first insert for a ticker, THE row SHALL be created with the incoming Observation_Timestamp when one is present, and with a null timestamp when it is absent — the latter reading `UNKNOWN` rather than fresh.
30. THE existing `updated_at` field SHALL retain its Receive_Time meaning and SHALL NOT be used as a freshness input.
31. WHEN a price row exists but carries no Observation_Timestamp, THE Freshness_State SHALL be `UNKNOWN`, and SHALL NOT be treated as fresh.
32. WHEN no price row exists for a holding, THE Freshness_State SHALL be `MISSING`, which is a distinct condition from `UNKNOWN`: the former has no price at all, the latter has a price of undeterminable age.
33. THE portfolio summary SHALL report Asset_Price_Freshness independently of Partial_Valuation, including: an overall Freshness_State, the oldest-known asset-price Observation_Timestamp defined in 9.35, a Stale_Holding count, an **Unknown_Price_Holding count**, and a Missing_Price_Holding count. The unknown count is reported separately because an `UNKNOWN` holding is neither stale nor missing, and would otherwise be invisible in the response despite affecting the overall state.
34. THE overall Freshness_State SHALL be the most severe per-holding state under Freshness_Precedence (`MISSING` > `UNKNOWN` > `STALE` > `FRESH`).
35. THE field previously called "oldest contributing Observation_Timestamp" SHALL be named to state exactly what it covers — `oldestKnownAssetPriceObservationTimestamp` or equivalent — and SHALL be computed over **all held assets with a known Observation_Timestamp**, not over the subset contributing to `totalValue`. "Contributing" was wrong: Missing_Price_Holdings and holdings excluded for an unavailable FX rate are both held and both absent from the total, so a name implying contribution describes neither the numerator nor the population.
36. WHERE no held asset has a known Observation_Timestamp, THE field SHALL be reported absent rather than as an arbitrary sentinel date. Its absence SHALL NOT by itself be read as freshness — the Unknown_Price_Holding count and the overall Freshness_State carry that information.
37. WHEN a portfolio has no holdings, THE overall Freshness_State SHALL be `FRESH`, all **three** freshness counts — Stale_Holding, Unknown_Price_Holding, and Missing_Price_Holding — SHALL be zero, the oldest Observation_Timestamp SHALL be absent, and Partial_Valuation SHALL be `false`. The precedence reduction has no result over an empty set, so this case is defined explicitly rather than left to the implementation; an empty portfolio has no stale valuation input and its `totalValue` of zero is exact.
38. THE meaning of Partial_Valuation SHALL remain "a holding was excluded from the total", and SHALL NOT be extended to mean staleness.
39. A Stale_Holding SHALL remain included in `totalValue` at its last known price.
40. AN Unknown_Price_Holding SHALL remain included in `totalValue` at its last known price, like a Stale_Holding and unlike a Missing_Price_Holding — it has a usable price, only an undeterminable age.
41. A Missing_Price_Holding SHALL be excluded from `totalValue` and SHALL set Partial_Valuation to `true`.
42. THE Freshness_Threshold SHALL be defined as `(N × Refresh_Cycle) + grace`, with `N` and `grace` configurable, and SHALL be documented with the concrete default values in effect.
43. THE default Freshness_Threshold SHALL NOT report stale for the normal price age produced by a single successful daily refresh.
44. THE Freshness_State computation SHALL be a pure function of **price-row presence**, the Observation_Timestamp, the Freshness_Threshold, and the evaluation time. Row presence is an explicit input because `MISSING` and `UNKNOWN` are otherwise indistinguishable — both lack a timestamp — and a function taking only the timestamp could not return both.
45. THE pure function in 9.44 SHALL be directly testable without a running refresh or a populated database.
46. THE contract defined by this requirement is **Asset_Price_Freshness only**. It SHALL NOT describe Fx_Rate_Age. A non-base-currency holding contributes `quantity × asset price × FX rate` to `totalValue` (`PortfolioService.java:~141`), so a `FRESH` asset price does not imply a fresh total.
47. THE response field naming and documentation SHALL make that scope explicit, so a reader cannot mistake asset-price freshness for whole-valuation freshness. Reporting `FRESH` under a name implying the total would repeat, in a new field, exactly the overclaim `partialValuation: false` makes today.
48. THE increment SHALL NOT persist FX rate observation timestamps, extend Freshness_Precedence across FX inputs, or report an FX freshness state. Fx_Rate_Age has a separate provider, a separate refresh schedule, and no timestamp persistence today; covering it means new storage and a second precedence dimension. It is recorded as a follow-up, not silently omitted.
49. THE existing behaviour whereby an **unavailable** FX rate excludes a holding and sets Partial_Valuation SHALL be unchanged. That is an availability signal, not an age signal, and it already works.

### Requirement 10: Catalog load failure is fatal

**User Story:** As the operator, I want a service that cannot load its catalog to refuse to start, so that it cannot serve traffic while silently unable to enforce or apply the catalog.

#### Acceptance Criteria

1. WHEN a Catalog_Load_Failure occurs, THE affected service SHALL fail to start.
2. THIS SHALL apply to every catalog-consuming service — `portfolio-service`, `market-data-service`, and `insight-service` — not only those enforcing the Write_Boundary, because an empty catalog in `market-data-service` would silently produce an empty Refresh_Desired_Set.
3. THE existing behaviour in `TickerCatalogService` of logging an error, setting an empty catalog, and continuing SHALL be removed.
4. THE rule SHALL apply in every normal application profile, not only production profiles; local and integration environments SHALL package a real Canonical_Manifest.
5. Isolated unit tests MAY inject explicit catalog fixtures, provided no runtime fallback path exists in application code.
6. THE increment SHALL NOT introduce a fallback to an empty, cached, or previous-version catalog.
7. A Catalog_Load_Failure SHALL emit a distinct structured event `catalog_load_failed` and a distinct exception type.
8. A request-level `unsupported_asset` error SHALL NOT be capable of representing catalog unavailability, because the two conditions have different causes and different operator responses.
9. Deployment verification SHALL confirm every deployed service reports the same Catalog_Version.

### Requirement 11: The seeder never writes global prices — price-write invariant DELIVERED, retained as a regression boundary

**Status: the price-write invariant is satisfied**, delivered by PR #97, merged as `b49ed01` and deployed on 2026-08-15 (revision `portfolio-service--0000054`). This requirement is retained rather than removed because it is the boundary a future change would have to cross to reintroduce the defect, and because the tests below are the standing guard on it. It is not work to schedule.

**User Story:** As the operator, I want portfolio seeding to be incapable of overwriting live market prices, so that production prices are not replaced by synthetic values.

#### Acceptance Criteria

1. THE seed path SHALL write portfolios and holdings and SHALL perform no write to the Global_Price_Table or `market_price_history`. *(Satisfied: the price and history batches were removed.)*
2. THE production-reachable endpoint `POST /api/internal/portfolio/seed` SHALL be holdings-only. This is unconditional: the endpoint is invoked against `https://api.vibhanshu-ai-portfolio.dev` daily at 08:00 UTC by `.github/workflows/synthetic-monitoring.yml` under `vars.CLOUD_PROVIDER == 'azure'`, the current repository variable value. *(Satisfied.)*
3. THE endpoint's response contract SHALL NOT report seeded price counts, and the field SHALL be **absent rather than zero**, so a stale consumer fails loudly instead of reading a plausible `0`. *(Satisfied: `marketPricesUpserted` removed from `SeedResult`, the controller, and all consumers.)*
4. THE synthetic price-seeding capability SHALL be **absent from, or disabled in, every production profile** — not merely separated by method, route, or bean. Route separation alone is insufficient: it leaves another production-reachable endpoint or bean able to expose the same capability, which is precisely how the original defect arrived. *(Satisfied in the strongest available form: the seed path retains no price-write code and `PortfolioSeedService` no longer holds a JDBC collaborator, so the seeding capability is absent rather than gated. This is scoped to the seed path — the Kafka projection's `JdbcTemplate` and its writes to both tables remain, correctly, per 11.6.)*
5. NO **seed or reset capability** in `portfolio-service` — seed endpoint, seed-specific bean or collaborator, or scheduled seed path — SHALL be able to write the Global_Price_Table or `market_price_history`, in any profile. *(Satisfied: no such capability remains in the seed path.)*
6. THE Kafka price projection (`MarketPriceProjectionService`, driven by `PriceUpdatedEventListener`) is **explicitly preserved** and is outside this prohibition. It is the legitimate production writer of both tables and the mechanism Requirement 9 specifies. A blanket ban on price writes from `portfolio-service` would forbid it, making Requirements 9 and 11 mutually unsatisfiable. *(Satisfied: the projection is untouched by PR #97 and continues to write both tables.)*
7. THE deterministic cost-basis behaviour derived from `(ticker, userId)` SHALL be preserved for seeded holdings, using the Seed_Only_Interface of Requirement 2.11. *(Satisfied: cost basis is computed in-memory from `basePrice` and was never read back from the price table.)*
8. A test SHALL snapshot **every column of every row** of both price tables before and after seeding, in deterministic order, and assert them identical — not a per-ticker price map and a row count, which would miss a scale change or a timestamp rewrite. *(Satisfied: `PortfolioSeedServiceIT`, across two seed invocations.)*
9. THAT test SHALL include sentinel rows under a ticker absent from the Supported_Catalog, so a wholesale wipe-and-rewrite is detected even though no catalogue ticker would appear to change. *(Satisfied.)*
10. A fast contract test SHALL pin the seed response body and assert the price-count field absent. *(Satisfied: `PortfolioSeedControllerTest`.)*

### Requirement 12: Non-goals

**User Story:** As a reviewer, I want boundaries stated, so that scope creep is visible rather than inferred.

#### Acceptance Criteria

1. THE increment SHALL NOT deliver `GET /api/assets`, the composition API, optimistic concurrency, the picker UI, or demo reset and presence behaviour; those belong to `asset-picker-composition`.
2. THE increment SHALL NOT introduce a relational `assets` table or a foreign key from `asset_holdings`, because application-level validation against a small static catalog is proportionate and a table would duplicate the Canonical_Manifest and risk blurring ownership with market-price data.
3. THE increment SHALL NOT support assets outside the Supported_Catalog; user-defined custom assets remain a separate roadmap item.
4. THE increment SHALL NOT alter refresh cadence, the ACA Job's role, or the skip-and-keep-last-price behaviour.
5. THE increment SHALL NOT resolve the `TATAMOTORS.NS` Corporate_Action_Migration.
6. THE increment SHALL NOT change `insight-service`'s natural-language ticker resolution behaviour — its alias handling, normalization, and grounding view are untouched (Requirement 2.5). It **does** change how that service behaves on a Catalog_Load_Failure, per Requirement 10.2–10.3: it will fail to start rather than continue with an empty catalog. Those are different concerns and both hold; the non-goal is about resolution semantics, not load-failure handling.
7. THE increment SHALL NOT deliver a frontend change or an interactive portfolio-composition feature. It does change the portfolio-summary HTTP response (Requirement 9), and Requirement 11 already changed the internal seed endpoint's response, so this work is not free of contract change and SHALL NOT be described as such.
8. THE increment SHALL NOT re-litigate Requirement 11's price-write invariant, which is delivered. Its acceptance criteria are retained as a regression boundary and as the specification the standing tests enforce. The forward-looking criterion that once sat here has been removed; the constraint it carried now lives in D20, which binds `asset-picker-composition`.

## Recorded Decisions and Constraints

These are settled decisions and accepted constraints. They are not acceptance criteria because they describe rationale and posture rather than verifiable system behaviour; the behaviour they imply is covered by the numbered requirements above.

### D1 — The invariant lives at the write boundary, not in the picker

The Asset Picker is not the enforcement mechanism. Sourcing its options from the Supported_Catalog makes the correct choice the easy one, but every other writer — the Golden_State_Seeder, Flyway migrations, tests, and any future API — must satisfy the same rule independently. A picker in front of an unvalidated endpoint closes nothing.

### D2 — Versioned embedded catalog, not a runtime catalog service and not a relational table

Three candidate architectures were considered. A **minimal patch** retaining the separate loaders and copies, adding only validation, was rejected: it preserves the drift risk and leaves three implementations able to disagree. A **relational `Asset` authority** was rejected as disproportionate for a small curated catalog and likely to blur identity ownership with market-price ownership; it becomes reconsiderable when corporate actions, custom assets, or transaction lots arrive, and if introduced it should own identity and metadata but never `current_price`. The **versioned embedded shared catalog** was selected: one source, no runtime service dependency, no cross-service call on the write path, and validation in-process where `portfolio-service` already loads the file.

### D3 — Emergent state must not define product capability

Mongo contents are observed state — a record of what was seeded historically. Deriving the Refresh_Desired_Set from them means the product's supported universe is defined by its own past accidents, which is exactly why `MM.NS` and `TATAMOTORS.NS` persist despite appearing in no baseline. The Supported_Catalog declares capability; the datastore records observation.

### D4 — Ordering constraint on catalog authority

Correcting symbols after making the catalog authoritative would briefly promote two known-broken symbols from emergent history into declared capability — strictly worse than the current state, and user-visible if the picker were reading the same list. Requirement 4.7 forbids that intermediate state. Lifecycle_Status is the mechanism that makes this expressible.

### D5 — Aliases are not accepted on the financial write path

`insight-service` resolves aliases and normalizes tokens because natural-language input demands it, and a wrong guess there produces a bad answer the user can see and correct. The same leniency on a write path produces a persisted holding in the wrong instrument, silently. Requirement 6.5 restricts write validation to exact canonical tickers, and Requirement 2.5 keeps normalization in `insight-service`.

### D6 — Fixed catalog counts are not an invariant

Prior analysis characterised the catalog as "160 assets, 50/50/50/10 by class". That is a product description, not schema law: `TATAMOTORS.NS` alone demonstrates that corporate actions change the set. Validating uniqueness, required metadata, lifecycle consistency, and at-least-one-active-per-class is durable; asserting counts would fail on the first legitimate change.

### D7 — Staleness is reported, never resolved by exclusion

A provider miss followed by retaining the last known price is intended and preserved. A price that can never update, reported as complete, is the defect. The remedy is visibility, not exclusion. Partial_Valuation is not overloaded to carry staleness because exclusion and staleness have different causes, different user meanings, and different remedies — Requirement 9 keeps them as separate reported facts.

### D8 — Four invariants, named separately

An earlier draft described "every holding is refreshable" as one rule, which contradicts itself once deprecation exists. The four are distinct and are tested distinctly: the **Referential_Invariant** (every holding resolves to a catalog entry) always holds; the **New_Write_Invariant** (new or replaced holdings are active) applies only at write time; the **Refresh_Invariant** (desired set equals active set) governs the refresh job; and the **Deprecated_Position** rule permits legacy positions to persist, be valued, and be removed, but not created or increased. Conflating them would produce a test that fails correctly-behaving code.

### D9 — `basePrice` is quarantined but versioned

An earlier draft forbade exposing `basePrice` to any consumer, which contradicted the requirement to preserve deterministic seeded cost bases — the seeder derives them from `basePrice` directly. The rationale was also imprecise: exposing a seed price did not corrupt valuation; **writing derived synthetic prices into the global projection did**. The Seed_Only_Interface resolves the access question — `basePrice` remains reachable for reproducible seeded cost bases and unreachable from catalog queries, validation, the asset API, and valuation.

That left a second question, resolved here: **`basePrice` is included in the Catalog_Version.** The two facts sit in tension only if "behaviourally relevant" is read as "widely visible". It is not: a change to `basePrice` changes every seeded cost basis, so two builds differing only in `basePrice` are behaviourally different, and a version that did not move would report them as identical. Narrow exposure and version participation are independent properties. The alternative — narrowing the Catalog_Version to "supported-catalog semantics only" — was rejected because it would make the version silently incomplete for exactly the consumer that depends on the excluded field.

### D10 — Receive time is not a freshness signal

`market_prices.updated_at` is written as `now()` and its `ON CONFLICT` clause suppresses the update entirely when price and currency are unchanged. Building freshness on it would report a correctly-polled stable quote as stale and a delayed event as fresh — inverted in both directions. `PriceUpdatedEvent` already carries `observedAt`, which the history path consumes and the projection discards; Requirement 9 persists it. This was found in review, after an earlier draft specified freshness with no examination of what would supply it.

### D11 — Redis availability is not a constraint on this spec

An earlier claim that `portfolio-service` lacks Redis in Azure production is **incorrect and is corrected here**. It runs `SPRING_PROFILES_ACTIVE=prod,azure`, Terraform supplies `REDIS_URL` (`infrastructure/terraform/azure/main.tf:306`), `application-prod.yml` maps it to `spring.data.redis.url`, and `InfrastructureHealthLogger` requires a `RedisConnectionFactory` and pings it at startup. `application-azure.yml` sets `spring.cache.type: none` so a Caffeine `CacheManager` serves the `@Cacheable` layer — a choice of backend for one layer, not a statement about Redis availability. The `CacheConfig` javadoc wording that prompted the error is misleading and should be corrected. Nothing here depends on Redis; the correction is recorded so it is not reasoned from again.

### D12 — Why this is a separate spec from the picker

Everything here is a correctness prerequisite that can be deployed and verified in production before any interactive behaviour changes. Bundling it with the picker would produce one change spanning build packaging, a shared module extraction, a database migration over live data, refresh configuration, valuation semantics, an HTTP contract, and frontend work — with no intermediate point at which the invariant could be confirmed holding in production. Two specs do not imply two large pull requests; a sensible delivery shape is several focused ones beneath each.

### D13 — The price tuple is atomic, and equal timestamps are not last-write-wins

Guarding only against timestamp regression is insufficient: an older observation could still overwrite the price while the newer timestamp remained, producing an old price that reads as fresh — the precise failure this spec exists to eliminate, reintroduced by the mechanism meant to detect it. Price, currency, and Observation_Timestamp therefore move as one tuple, written only on a strictly newer observation.

Equal timestamps are treated in two ways rather than one. An identical payload is a no-op, so redelivery is idempotent. A **conflicting** payload at the same instant is rejected and surfaced, because two different prices claiming one observation time is an upstream fault; silently taking the last one would hide a data-quality problem behind a valuation that still looks well-formed.

### D14 — `MISSING` and `UNKNOWN` are different conditions with different remedies

Both lack an Observation_Timestamp, so a freshness function taking only the timestamp cannot distinguish them — which is why price-row presence is an explicit input to the pure function rather than an implicit precondition. They differ in what they mean and what they cost: `MISSING` has no price at all, is excluded from `totalValue`, and sets Partial_Valuation; `UNKNOWN` has a price of undeterminable age which is still included. Collapsing them would either exclude priced holdings from the total or include unpriced ones at zero.

Freshness_Precedence ranks `UNKNOWN` above `STALE` deliberately. A stale price's age is bounded and reportable, so a reader can judge whether it matters; an unknown one's age is unbounded and could be arbitrarily worse. Ranking it below `STALE` would let the more dangerous condition be masked by the less dangerous one.

### D15 — The repair spans two stores, and only one is Flyway's

An earlier draft placed Mongo state under a requirement that all migrations be "idempotent under Flyway re-execution". Flyway manages the Postgres schema only, so that requirement was unsatisfiable as written for the Mongo half. The Postgres_Repair and the Mongo_Repair are therefore separate, each with its own idempotency mechanism and its own completion evidence, with an explicit and interruptible ordering between them. The ordering constraint is real: neither store may be left enforcing a symbol the other cannot resolve.

### D16 — What closed the seeder incident

Recorded because the reasoning was corrected once and should not be re-derived incorrectly. Closure did **not** rest on the production log line being independently stronger than a table diff — that framing was wrong. It rests on a chain, no link of which suffices alone: the deployed image SHA matching the merge commit; source review confirming no price-write path reachable from the seed endpoint or the seeder in that artifact — the Kafka projection's writes are legitimate and were never in question; a production log line proving that artifact executed a real 160-holding seed; and a full-table integration regression test asserting byte-identity of both price tables with sentinel rows.

Similarly, the earlier backlog observation was not "proven uncontaminated". The supported claim is narrower and stronger: **those specific values were not generated by the removed seeder**, because its deterministic formula would have produced `BTC-USD 68,106.3200`, `AAPL 197.3396`, and `TSLA 183.2891` for the E2E user, against observed values of `62,944.49`, `305.93`, and `342.27`.

### D17 — The null-timestamp rule is deliberately asymmetric

"Strictly newer" is undefined against a null, and nulls are the normal starting condition rather than an edge case: every existing price row acquires one when the column is added, and `PriceUpdatedEvent.observedAt` is nullable by design for old-shape events. Leaving the comparison to the implementation would have produced whatever the SQL happened to do with `NULL`, which is silently false for every operator.

The rule is asymmetric on purpose. A known timestamp arriving over a stored null is **accepted** — that is how legacy rows acquire provenance, and it is a strict information gain. A null arriving over a stored known timestamp is **rejected entirely**, because accepting it would downgrade a dated price to an undatable one, turning a `FRESH` or `STALE` holding into `UNKNOWN` and destroying exactly the evidence that distinguishes them.

Null over null **writes** — deterministically, not at the implementation's discretion. An earlier draft said `MAY`, which is not a specification: neither the implementation nor a test could derive one expected result from it. Where two undated payloads conflict, the later-received wins, because Receive_Time is the only ordering available when neither side carries an observation time. That is last-write-wins by necessity rather than preference, and it is bounded: such a row always reports `UNKNOWN`, so it never claims an age it cannot support. The projection emits an observable signal on this path so that a producer emitting undated events is visible rather than silently degrading every row it touches.

### D18 — What the Catalog_Version can and cannot verify

The Catalog_Version is a hash. It proves that two services loaded the same catalog; it cannot prove how many entries that catalog has, so it cannot stand in for a count assertion. Requirement 8 therefore asserts the two things separately: seeded holdings equal to `Active_Asset` cardinality, and Catalog_Version equality across deployed services as an independent identity check.

The prohibition on literal counts is scoped to tests exercising the canonical or live catalog, not to all tests. A fixture catalog constructed with two entries must be able to assert `2` — that is the fixture's own contract, fully determined by the test, and forbidding it would make deterministic unit testing of the Catalog_Module impossible. The defect being prevented is a test that hard-codes today's *production* catalog size and thereby breaks on the first legitimate corporate action.

### D19 — Freshness is scoped to asset prices, and the name says so

`totalValue` multiplies three inputs: quantity, asset price, and — for non-base-currency holdings — an FX rate (`PortfolioService.java:~141`). An earlier draft specified freshness over the asset price alone while calling it "valuation freshness", which would have let the API report `FRESH` with a stale or undated FX rate underneath. That is the same overclaim as today's `partialValuation: false`, relocated into a new field.

Two resolutions were available: extend the contract to cover FX age, or narrow it and say so. **Narrowing was chosen**, and the contract is renamed Asset_Price_Freshness accordingly, because the honesty problem is in the *name*, not the scope. A correctly named narrow contract misleads nobody; a broadly named narrow one misleads everybody.

Extending was rejected on proportionality, not principle. FX rates have a different provider, a different refresh schedule, and — decisively — **no timestamp persistence at all** today, since `EcbFxRateProvider` caches rather than stores them. Covering FX age therefore means new storage plus a second dimension in Freshness_Precedence, in a spec already spanning build packaging, a shared module, two-store migration, and valuation semantics. Fx_Rate_Age is named in the glossary and recorded as a follow-up so the gap is visible rather than absent.

The existing behaviour for an **unavailable** FX rate is untouched: the holding is excluded and Partial_Valuation is set. Availability and age are different questions, and the availability half already works.

### D20 — The holdings-only seed constraint binds `asset-picker-composition`

Requirement 11 previously carried an acceptance criterion reading "any demo repair or reset mechanism SHALL use the holdings-only path". It was removed, not weakened. It was the single forward-looking criterion inside a requirement marked delivered, and nothing in this spec's design or tests could trace to it, because the mechanism it governs is specified elsewhere. "Vacuously satisfied" is logically true and useless for traceability.

The constraint survives in two places that can actually be verified. Within this spec, Requirement 8.6 requires the Demo_Portfolio to be seeded via the Holdings_Only_Seed path — testable now, against real behaviour. Beyond it, **`asset-picker-composition` inherits the rule**: its demo reset mechanism must use the holdings-only path and must not write the Global_Price_Table or `market_price_history`. That is where the reset is designed, where a test can exercise it, and therefore where the criterion belongs.

Requirement 11's remaining criteria all describe shipped behaviour and each carries a satisfaction note, so the requirement is now uniformly a regression boundary rather than a mixture of record and intent.
