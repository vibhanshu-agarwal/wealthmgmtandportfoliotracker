package com.wealth.portfolio;

import com.wealth.portfolio.dto.PortfolioAnalyticsDto;
import com.wealth.portfolio.dto.PortfolioAnalyticsDto.HoldingAnalyticsDto;
import com.wealth.portfolio.dto.PortfolioAnalyticsDto.PerformerDto;
import com.wealth.portfolio.dto.PortfolioAnalyticsDto.PerformanceCoverageDto;
import com.wealth.portfolio.dto.PortfolioAnalyticsDto.PerformancePointDto;
import com.wealth.portfolio.fx.FxProperties;
import com.wealth.portfolio.seed.SeedTickerRegistry;
import com.wealth.user.UserRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes the full portfolio analytics payload for {@code GET /api/portfolio/analytics}.
 *
 * <p>Uses a single SQL round-trip (CTE + UNION ALL) to retrieve holdings, cost-basis fields,
 * 24h tolerance-window reference prices, and historical series data. FX conversion is applied
 * per holding. Results are cached per-user with a profile-appropriate backend.
 *
 * <h2>Task 5 correctness properties</h2>
 * <ul>
 *   <li><b>5.1 Real P&amp;L</b>: unrealised P&amp;L is computed from {@code avg_cost_basis}
 *       FX-converted from {@code cost_basis_currency} to base currency. Null when basis is absent.
 *       Never coerced to 0.</li>
 *   <li><b>5.2 Tolerance-window change</b>: 24h reference is the closest {@code observed_at}
 *       that falls within the ≈18–36h window. Returns the reference timestamp and a
 *       {@code changeBasis} label. Null when no in-window row exists.</li>
 *   <li><b>5.3 Performance coverage</b>: the series includes coverage metadata. When some
 *       holdings lack history the series is marked partial and must not be presented as
 *       full-portfolio. A synthetic fallback is clearly labelled {@code synthetic=true}.</li>
 *   <li><b>5.4 Asset-class mapping</b>: canonical display asset class is resolved from
 *       {@link SeedTickerRegistry} via {@link DisplayAssetClassMapper}. Unknown tickers → OTHER.</li>
 * </ul>
 */
@Service
public class PortfolioAnalyticsService {
    private static final Logger log = org.slf4j.LoggerFactory.getLogger(PortfolioAnalyticsService.class);

    private static final int DEFAULT_PERIOD_DAYS = 50;
    private static final int SYNTHETIC_THRESHOLD = 7;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final PerformerDto SENTINEL_PERFORMER = new PerformerDto("N/A", null);

    /**
     * Single SQL query returning two row types:
     * <ul>
     *   <li>HOLDING — one row per holding; includes cost-basis fields and the best available
     *       reference price for 24h change, with its {@code observed_at} and a label column
     *       that distinguishes in-window (≈18–36h) from best-available-snapshot references.</li>
     *   <li>HISTORY — one row per (ticker, date) within the requested period.</li>
     * </ul>
     *
     * <h3>Reference selection strategy (Task 5.2)</h3>
     * <p>Two CTEs compete per ticker:
     * <ol>
     *   <li>{@code price_in_window} — the most-recent row in the ≈18–36h tolerance window
     *       (closest to 24h ago); labelled {@code WITHIN_24H_WINDOW}.</li>
     *   <li>{@code price_snapshot} — the most-recent row <em>before</em> the window, i.e.
     *       older than 36h; used as a fallback when no in-window row exists, labelled
     *       {@code SINCE_PREVIOUS_SNAPSHOT}.</li>
     * </ol>
     * <p>{@code COALESCE} prefers the in-window row. When neither exists the reference columns
     * are null and the change is reported as unavailable.
     *
     * <p>Cost-basis columns ({@code avg_cost_basis}, {@code cost_basis_currency}) are fetched
     * from {@code asset_holdings} for Task 5.1 real P&amp;L.
     */
    private static final String ANALYTICS_SQL = """
            WITH user_tickers AS (
                SELECT h.asset_ticker,
                       h.quantity,
                       h.avg_cost_basis,
                       h.cost_basis_currency,
                       mp.current_price,                               -- null when no market_prices row
                       mp.quote_currency                               -- null when no market_prices row
                FROM asset_holdings h
                JOIN portfolios p ON p.id = h.portfolio_id
                LEFT JOIN market_prices mp ON mp.ticker = h.asset_ticker
                WHERE p.user_id = ?
            ),
            price_in_window AS (
                SELECT DISTINCT ON (mph.ticker)
                       mph.ticker,
                       mph.price        AS price_ref,
                       mph.observed_at  AS price_ref_at,
                       'WITHIN_24H_WINDOW'::VARCHAR AS ref_label
                FROM market_price_history mph
                JOIN user_tickers ut ON ut.asset_ticker = mph.ticker
                WHERE mph.observed_at BETWEEN now() - INTERVAL '36 hours'
                                          AND now() - INTERVAL '18 hours'
                ORDER BY mph.ticker, mph.observed_at DESC
            ),
            price_snapshot AS (
                SELECT DISTINCT ON (mph.ticker)
                       mph.ticker,
                       mph.price        AS price_ref,
                       mph.observed_at  AS price_ref_at,
                       'SINCE_PREVIOUS_SNAPSHOT'::VARCHAR AS ref_label
                FROM market_price_history mph
                JOIN user_tickers ut ON ut.asset_ticker = mph.ticker
                WHERE mph.observed_at < now() - INTERVAL '36 hours'
                ORDER BY mph.ticker, mph.observed_at DESC
            ),
            best_ref AS (
                SELECT
                    COALESCE(w.ticker, s.ticker)             AS ticker,
                    COALESCE(w.price_ref,  s.price_ref)      AS price_24h_ago,
                    COALESCE(w.price_ref_at, s.price_ref_at) AS price_24h_ref_at,
                    COALESCE(w.ref_label,  s.ref_label)      AS ref_label
                FROM price_in_window w
                FULL OUTER JOIN price_snapshot s ON s.ticker = w.ticker
            )
            SELECT 'HOLDING'                   AS row_type,
                   ut.asset_ticker,
                   ut.quantity,
                   ut.current_price,
                   ut.quote_currency,
                   br.price_24h_ago,
                   br.price_24h_ref_at,
                   br.ref_label,
                   ut.avg_cost_basis,
                   ut.cost_basis_currency,
                   NULL::DATE                  AS history_date,
                   NULL::NUMERIC               AS history_price
            FROM user_tickers ut
            LEFT JOIN best_ref br ON br.ticker = ut.asset_ticker
            UNION ALL
            SELECT 'HISTORY'                   AS row_type,
                   mph.ticker                  AS asset_ticker,
                   NULL::NUMERIC               AS quantity,
                   NULL::NUMERIC               AS current_price,
                   ut.quote_currency,
                   NULL::NUMERIC               AS price_24h_ago,
                   NULL::TIMESTAMP             AS price_24h_ref_at,
                   NULL::VARCHAR               AS ref_label,
                   NULL::NUMERIC               AS avg_cost_basis,
                   NULL::VARCHAR               AS cost_basis_currency,
                   mph.observed_at::DATE        AS history_date,
                   mph.price                   AS history_price
            FROM market_price_history mph
            JOIN user_tickers ut ON ut.asset_ticker = mph.ticker
            WHERE mph.observed_at >= now() - (? * INTERVAL '1 day')
            ORDER BY row_type, asset_ticker, history_date
            """;

    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;
    private final PortfolioRepository portfolioRepository;
    private final FxRateProvider fxRateProvider;
    private final FxProperties fxProperties;
    private final SeedTickerRegistry seedTickerRegistry;

    public PortfolioAnalyticsService(JdbcTemplate jdbcTemplate,
                                     UserRepository userRepository,
                                     PortfolioRepository portfolioRepository,
                                     FxRateProvider fxRateProvider,
                                     FxProperties fxProperties,
                                     SeedTickerRegistry seedTickerRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.userRepository = userRepository;
        this.portfolioRepository = portfolioRepository;
        this.fxRateProvider = fxRateProvider;
        this.fxProperties = fxProperties;
        this.seedTickerRegistry = seedTickerRegistry;
    }

    /**
     * Returns the full analytics payload for the given user.
     * Results are cached per-user; the cache key is scoped to prevent cross-user leakage.
     *
     * @param userId authenticated user UUID (from X-User-Id header)
     * @return assembled {@link PortfolioAnalyticsDto}
     * @throws UserNotFoundException if userId is not present in the user's table
     */
    @Cacheable(value = "portfolio-analytics", key = "#userId")
    @Transactional(readOnly = true)
    public PortfolioAnalyticsDto getAnalytics(String userId) {
        requireUserExists(userId);

        String baseCurrency = fxProperties.baseCurrency();

        List<AnalyticsQueryRow> rows = jdbcTemplate.query(
                ANALYTICS_SQL,
                (rs, i) -> {
                    Timestamp refAtTs = rs.getTimestamp("price_24h_ref_at");
                    Instant refAt = refAtTs != null ? refAtTs.toInstant() : null;
                    return new AnalyticsQueryRow(
                            rs.getString("row_type"),
                            rs.getString("asset_ticker"),
                            rs.getBigDecimal("quantity"),
                            rs.getBigDecimal("current_price"),
                            rs.getString("quote_currency"),
                            rs.getBigDecimal("price_24h_ago"),
                            refAt,
                            rs.getString("ref_label"),
                            rs.getBigDecimal("avg_cost_basis"),
                            rs.getString("cost_basis_currency"),
                            rs.getString("history_date"),
                            rs.getBigDecimal("history_price")
                    );
                },
                userId,
                DEFAULT_PERIOD_DAYS
        );

        List<AnalyticsQueryRow> holdingRows = rows.stream()
                .filter(r -> "HOLDING".equals(r.rowType()))
                .toList();
        List<AnalyticsQueryRow> historyRows = rows.stream()
                .filter(r -> "HISTORY".equals(r.rowType()))
                .toList();

        if (holdingRows.isEmpty()) {
            return emptyAnalytics(baseCurrency);
        }

        // FX rate cache — at most one call per distinct quoteCurrency per request
        Map<String, BigDecimal> fxRateCache = new HashMap<>();
        // Track currencies whose rates are unavailable so aggregates can exclude them cleanly
        Set<String> unavailableCurrencies = new HashSet<>();

        // ── Per-holding computation ──────────────────────────────────────────
        List<HoldingAnalyticsDto> holdingDtos = new ArrayList<>();
        for (AnalyticsQueryRow row : holdingRows) {
            // Issue #1 fix: currentPrice null means no market_prices row for this ticker.
            // Treat as price-unavailable: null currentValueBase, null change, null P&L.
            // Never substitute 0 — a zero price produces a false 100% loss on any holding
            // with a cost basis and a false -100% change on any holding with history.
            boolean priceAvailable = row.currentPrice() != null;
            BigDecimal quoteRate = priceAvailable
                    ? fxRate(row.quoteCurrency(), baseCurrency, fxRateCache, unavailableCurrencies)
                    : null;

            // Issue #2 fix: emit null (not ZERO) when price or FX is unavailable.
            // Matches the documented contract on HoldingAnalyticsDto.currentValueBase.
            BigDecimal currentValueBase = (priceAvailable && quoteRate != null)
                    ? row.quantity().multiply(row.currentPrice()).multiply(quoteRate)
                            .setScale(4, RoundingMode.HALF_UP)
                    : null;

            // Task 5.1: real unrealised P&L from avg_cost_basis.
            // Cost basis may be in a DIFFERENT currency than quoteCurrency (e.g. basis captured
            // in INR for an NSE stock, now being compared against a USD base).
            // P&L = currentValueBase − (quantity × avgCostBasis × costBasisRate)
            // Null when avg_cost_basis is absent OR currentPrice is unavailable — never 0.
            BigDecimal avgCostBasis = row.avgCostBasis();
            String costBasisCurrency = row.costBasisCurrency();
            BigDecimal unrealizedPnL = null;
            BigDecimal unrealizedPnLPercent = null;

            if (avgCostBasis != null && currentValueBase != null) {
                BigDecimal costBasisRate = fxRate(
                        costBasisCurrency != null ? costBasisCurrency : row.quoteCurrency(),
                        baseCurrency, fxRateCache, unavailableCurrencies);
                if (costBasisRate != null) {
                    BigDecimal totalCostBase = row.quantity().multiply(avgCostBasis).multiply(costBasisRate)
                            .setScale(4, RoundingMode.HALF_UP);
                    unrealizedPnL = currentValueBase.subtract(totalCostBase).setScale(4, RoundingMode.HALF_UP);
                    if (totalCostBase.compareTo(BigDecimal.ZERO) != 0) {
                        unrealizedPnLPercent = unrealizedPnL
                                .divide(totalCostBase, new MathContext(10, RoundingMode.HALF_UP))
                                .multiply(HUNDRED)
                                .setScale(4, RoundingMode.HALF_UP);
                    } else {
                        unrealizedPnLPercent = BigDecimal.ZERO;
                    }
                }
            }

            // Task 5.2: tolerance-window change — null when no reference exists OR price unavailable.
            // changeBasis comes directly from the SQL query (WITHIN_24H_WINDOW or SINCE_PREVIOUS_SNAPSHOT).
            BigDecimal change24hAbs = priceAvailable
                    ? computeChange24hAbsolute(row.currentPrice(), row.price24hAgo())
                    : null;
            BigDecimal change24hPct = priceAvailable
                    ? computeChange24hPercent(row.currentPrice(), row.price24hAgo())
                    : null;
            String referenceAt = row.price24hReferenceAt() != null
                    ? row.price24hReferenceAt().toString()
                    : null;
            String changeBasis = row.refLabel();

            // Task 5.4: canonical display asset class from seed registry
            String displayAssetClass = resolveDisplayAssetClass(row.assetTicker());

            holdingDtos.add(new HoldingAnalyticsDto(
                    row.assetTicker(),
                    row.quantity(),
                    row.currentPrice(),   // null when no market_prices row — never 0
                    currentValueBase,     // null when price/FX unavailable — never 0
                    avgCostBasis,
                    costBasisCurrency,
                    unrealizedPnL,
                    unrealizedPnLPercent,
                    change24hAbs,
                    change24hPct,
                    referenceAt,
                    changeBasis,
                    row.quoteCurrency(),
                    displayAssetClass
            ));
        }

        // ── Aggregates — skip holdings whose price or FX rate was unavailable ──
        // Symmetric exclusion rule: a holding is excluded from BOTH totalValue and totalCostBasis
        // whenever its current value cannot be determined (null currentValueBase). This covers:
        //   (a) missing market_prices row  → currentPrice null  → currentValueBase null
        //   (b) quote-currency FX unavailable               → currentValueBase null
        //   (c) cost-basis-currency FX unavailable          → added to unavailableCostBasisTickers
        //
        // Excluding a holding from totalValue but keeping its basis in totalCostBasis would
        // produce a phantom aggregate loss equal to the full cost basis of every priced-absent
        // holding — the same "false 100% loss" problem that Issue #1 fixed at the per-holding level.
        Set<String> unavailableCostBasisTickers = new HashSet<>();
        for (AnalyticsQueryRow row : holdingRows) {
            // Problem B fix: skip missing-price rows — no currentValueBase means the holding
            // is already excluded from totalValue and must not contribute to totalCostBasis.
            // This also prevents a NPE when both costBasisCurrency and quoteCurrency are null
            // (quoteCurrency is null when there is no market_prices row).
            if (row.currentPrice() == null) continue;
            if (row.avgCostBasis() == null) continue;
            String cbCurrency = row.costBasisCurrency() != null
                    ? row.costBasisCurrency() : row.quoteCurrency();
            if (cbCurrency != null && !cbCurrency.equals(baseCurrency)
                    && unavailableCurrencies.contains(cbCurrency)) {
                unavailableCostBasisTickers.add(row.assetTicker());
            }
        }

        BigDecimal totalValue = holdingDtos.stream()
                .filter(h -> !unavailableCurrencies.contains(h.quoteCurrency()))
                .filter(h -> !unavailableCostBasisTickers.contains(h.ticker()))
                .filter(h -> h.currentValueBase() != null)   // null = price/FX unavailable
                .map(HoldingAnalyticsDto::currentValueBase)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Task 5.1: aggregate cost basis — only include holdings whose value is also counted
        // (symmetric with totalValue). Problem A fix: add currentValueBase != null filter so
        // a missing-price holding does not contribute basis without contributing value.
        BigDecimal totalCostBasis = holdingDtos.stream()
                .filter(h -> !unavailableCurrencies.contains(h.quoteCurrency()))
                .filter(h -> !unavailableCostBasisTickers.contains(h.ticker()))
                .filter(h -> h.currentValueBase() != null)   // symmetric: same guard as totalValue
                .filter(h -> h.avgCostBasis() != null)
                .map(h -> {
                    String cbCurrency = h.costBasisCurrency() != null ? h.costBasisCurrency() : h.quoteCurrency();
                    BigDecimal rate = fxRate(cbCurrency, baseCurrency, fxRateCache, unavailableCurrencies);
                    if (rate == null) return BigDecimal.ZERO;
                    return h.quantity().multiply(h.avgCostBasis()).multiply(rate)
                            .setScale(4, RoundingMode.HALF_UP);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Task 5.1: aggregate P&L — null when no priced, fully-convertible holding has a cost basis.
        // Excludes both missing-price holdings (currentValueBase == null) and holdings whose
        // cost-basis currency FX is unavailable (unavailableCostBasisTickers), ensuring the
        // null vs. zero decision is consistent with what actually lands in totalCostBasis.
        boolean anyHasBasis = holdingDtos.stream()
                .filter(h -> h.currentValueBase() != null)
                .filter(h -> !unavailableCostBasisTickers.contains(h.ticker()))
                .anyMatch(h -> h.avgCostBasis() != null);
        BigDecimal totalPnL;
        BigDecimal totalPnLPct;
        if (anyHasBasis) {
            totalPnL = totalValue.subtract(totalCostBasis);
            totalPnLPct = totalCostBasis.compareTo(BigDecimal.ZERO) > 0
                    ? totalPnL.divide(totalCostBasis, 4, RoundingMode.HALF_UP).multiply(HUNDRED)
                    : BigDecimal.ZERO;
        } else {
            totalPnL = null;
            totalPnLPct = null;
        }

        // Performers: only consider holdings that have a change value
        PerformerDto best = holdingDtos.stream()
                .filter(h -> h.change24hPercent() != null)
                .max(Comparator.comparing(HoldingAnalyticsDto::change24hPercent))
                .map(h -> new PerformerDto(h.ticker(), h.change24hPercent()))
                .orElse(SENTINEL_PERFORMER);

        PerformerDto worst = holdingDtos.stream()
                .filter(h -> h.change24hPercent() != null)
                .min(Comparator.comparing(HoldingAnalyticsDto::change24hPercent))
                .map(h -> new PerformerDto(h.ticker(), h.change24hPercent()))
                .orElse(SENTINEL_PERFORMER);

        // ── Performance series with coverage metadata (Task 5.3) ──────────
        PerformanceSeriesResult seriesResult = buildPerformanceSeries(
                historyRows, holdingRows, totalValue, baseCurrency, fxRateCache, unavailableCurrencies);

        return new PortfolioAnalyticsDto(
                totalValue,
                totalCostBasis,
                totalPnL,
                totalPnLPct,
                baseCurrency,
                !unavailableCurrencies.isEmpty(),
                best,
                worst,
                holdingDtos,
                seriesResult.series(),
                seriesResult.coverage()
        );
    }

    // ── Helper: 24h change (Task 5.2) ───────────────────────────────────────

    /**
     * Returns null when no reference exists (never coerces to 0).
     */
    BigDecimal computeChange24hPercent(BigDecimal currentPrice, BigDecimal price24hAgo) {
        if (price24hAgo == null) {
            return null;
        }
        if (price24hAgo.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return currentPrice.subtract(price24hAgo)
                .divide(price24hAgo, new MathContext(10, RoundingMode.HALF_UP))
                .multiply(HUNDRED)
                .setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Returns null when no reference exists.
     */
    BigDecimal computeChange24hAbsolute(BigDecimal currentPrice, BigDecimal price24hAgo) {
        if (price24hAgo == null) {
            return null;
        }
        return currentPrice.subtract(price24hAgo).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Derives the change-basis label from a reference timestamp.
     * This method is a utility for testing the label logic in isolation; at runtime
     * the label is computed by the SQL query ({@code ref_label} column) and stored
     * directly in {@link AnalyticsQueryRow#refLabel()}.
     *
     * <p>Labels:
     * <ul>
     *   <li>{@code "WITHIN_24H_WINDOW"} — reference falls in the ≈18–36h window.</li>
     *   <li>{@code "SINCE_PREVIOUS_SNAPSHOT"} — reference exists but is older than 36h.</li>
     *   <li>{@code null} — no reference.</li>
     * </ul>
     */
    String computeChangeBasis(Instant referenceAt) {
        if (referenceAt == null) {
            return null;
        }
        long hoursAgo = java.time.Duration.between(referenceAt, Instant.now()).toHours();
        if (hoursAgo >= 18 && hoursAgo <= 36) {
            return "WITHIN_24H_WINDOW";
        }
        return "SINCE_PREVIOUS_SNAPSHOT";
    }

    // ── Helper: performance series with coverage (Task 5.3) ─────────────────

    /**
     * Internal result container for the performance series + its coverage metadata.
     */
    record PerformanceSeriesResult(List<PerformancePointDto> series, PerformanceCoverageDto coverage) {}

    PerformanceSeriesResult buildPerformanceSeries(
            List<AnalyticsQueryRow> historyRows,
            List<AnalyticsQueryRow> holdingRows,
            BigDecimal totalValue,
            String baseCurrency,
            Map<String, BigDecimal> fxRateCache,
            Set<String> unavailableCurrencies) {

        int totalHoldings = holdingRows.size();

        // Group history rows by date
        Map<String, List<AnalyticsQueryRow>> byDate = historyRows.stream()
                .filter(r -> r.historyDate() != null)
                .collect(Collectors.groupingBy(AnalyticsQueryRow::historyDate));

        // Task 5.3: determine how many holdings have at least one history row
        Set<String> tickersWithHistory = historyRows.stream()
                .map(AnalyticsQueryRow::assetTicker)
                .collect(Collectors.toSet());
        Set<String> holdingTickers = holdingRows.stream()
                .map(AnalyticsQueryRow::assetTicker)
                .collect(Collectors.toSet());
        Set<String> holdingsWithHistory = new HashSet<>(holdingTickers);
        holdingsWithHistory.retainAll(tickersWithHistory);
        int coveredCount = holdingsWithHistory.size();
        boolean partial = coveredCount < totalHoldings;

        if (byDate.size() < SYNTHETIC_THRESHOLD) {
            // Not enough real history dates — return a synthetic series clearly labelled
            List<PerformancePointDto> synthetic = generateSyntheticSeries(totalValue, SYNTHETIC_THRESHOLD);
            PerformanceCoverageDto coverage = new PerformanceCoverageDto(
                    coveredCount, totalHoldings, true, true);
            return new PerformanceSeriesResult(synthetic, coverage);
        }

        // Build a ticker → holdingRow lookup for quantity
        Map<String, AnalyticsQueryRow> holdingByTicker = holdingRows.stream()
                .collect(Collectors.toMap(AnalyticsQueryRow::assetTicker, r -> r, (a, b) -> a));

        List<String> sortedDates = new ArrayList<>(byDate.keySet());
        Collections.sort(sortedDates);

        List<PerformancePointDto> series = new ArrayList<>();
        BigDecimal previousValue = null;

        for (String date : sortedDates) {
            BigDecimal dateValue = BigDecimal.ZERO;
            for (AnalyticsQueryRow histRow : byDate.get(date)) {
                AnalyticsQueryRow holding = holdingByTicker.get(histRow.assetTicker());
                if (holding != null && histRow.historyPrice() != null) {
                    BigDecimal rate = fxRate(histRow.quoteCurrency(), baseCurrency, fxRateCache, unavailableCurrencies);
                    if (rate == null) continue;
                    dateValue = dateValue.add(
                            holding.quantity()
                                    .multiply(histRow.historyPrice())
                                    .multiply(rate)
                                    .setScale(4, RoundingMode.HALF_UP)
                    );
                }
            }
            BigDecimal change = previousValue == null
                    ? BigDecimal.ZERO
                    : dateValue.subtract(previousValue).setScale(4, RoundingMode.HALF_UP);
            series.add(new PerformancePointDto(date, dateValue, change));
            previousValue = dateValue;
        }

        PerformanceCoverageDto coverage = new PerformanceCoverageDto(
                coveredCount, totalHoldings, partial, false);
        return new PerformanceSeriesResult(series, coverage);
    }

    /**
     * Generates a synthetic {@code days}-point performance series ending at {@code anchorValue}.
     * Used as a fallback labelled {@code synthetic=true} in the coverage metadata — must not be
     * presented to users as real portfolio data.
     *
     * <p>Invariants:
     * <ul>
     *   <li>Returns exactly {@code days} entries.</li>
     *   <li>Last entry's {@code value == anchorValue}.</li>
     *   <li>Entries are ordered ascending by date (today − (days−1) … today).</li>
     *   <li>First entry's {@code change == 0}; subsequent {@code change = value[i] − value[i−1]}.</li>
     * </ul>
     */
    List<PerformancePointDto> generateSyntheticSeries(BigDecimal anchorValue, int days) {
        List<PerformancePointDto> points = new ArrayList<>(days);
        LocalDate today = LocalDate.now();

        BigDecimal value = anchorValue.compareTo(BigDecimal.ZERO) > 0
                ? anchorValue.multiply(new BigDecimal("0.92")).setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        for (int i = days - 1; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            BigDecimal previous = value;

            if (anchorValue.compareTo(BigDecimal.ZERO) > 0) {
                double drift = anchorValue.doubleValue() * 0.001;
                double wave = Math.sin((days - 1 - i) / 3.0) * anchorValue.doubleValue() * 0.0025;
                double next = Math.max(value.doubleValue() + drift + wave,
                        anchorValue.doubleValue() * 0.85);
                value = BigDecimal.valueOf(next).setScale(4, RoundingMode.HALF_UP);
            }

            BigDecimal change = points.isEmpty()
                    ? BigDecimal.ZERO
                    : value.subtract(previous).setScale(4, RoundingMode.HALF_UP);

            points.add(new PerformancePointDto(date.toString(), value, change));
        }

        // Pin the last point to anchorValue
        if (!points.isEmpty()) {
            PerformancePointDto last = points.getLast();
            BigDecimal prevValue = points.size() > 1
                    ? points.get(points.size() - 2).value()
                    : anchorValue;
            BigDecimal pinnedChange = anchorValue.subtract(prevValue).setScale(4, RoundingMode.HALF_UP);
            points.set(points.size() - 1,
                    new PerformancePointDto(last.date(), anchorValue, pinnedChange));
        }

        return points;
    }

    // ── Helper: asset-class mapping (Task 5.4) ───────────────────────────────

    /**
     * Resolves the canonical display asset class for a ticker.
     * Falls back to {@link DisplayAssetClassMapper#OTHER} for unknown tickers.
     */
    String resolveDisplayAssetClass(String ticker) {
        return seedTickerRegistry.find(ticker)
                .map(t -> DisplayAssetClassMapper.map(t.assetClass()))
                .orElse(DisplayAssetClassMapper.OTHER);
    }

    // ── Helper: FX rate with per-request cache ───────────────────────────────

    /**
     * Returns the FX rate for the given currency pair, using a per-request cache.
     * Returns {@code null} if the rate is unavailable and records the currency in
     * {@code unavailableCurrencies} so callers can filter consistently.
     *
     * <p>Uses explicit put rather than computeIfAbsent because HashMap.computeIfAbsent
     * does not store null values — repeated calls for the same unavailable currency
     * would otherwise re-invoke the provider and re-log the warning on every access.
     */
    private BigDecimal fxRate(String quoteCurrency, String baseCurrency,
                              Map<String, BigDecimal> cache,
                              Set<String> unavailableCurrencies) {
        if (quoteCurrency == null || quoteCurrency.equals(baseCurrency)) {
            return BigDecimal.ONE;
        }
        if (unavailableCurrencies.contains(quoteCurrency)) {
            return null;
        }
        if (cache.containsKey(quoteCurrency)) {
            return cache.get(quoteCurrency);
        }
        try {
            BigDecimal rate = fxRateProvider.getRate(quoteCurrency, baseCurrency);
            cache.put(quoteCurrency, rate);
            return rate;
        } catch (FxRateUnavailableException e) {
            log.warn("FX rate unavailable for {} → {} — affected holdings excluded from aggregates",
                    quoteCurrency, baseCurrency);
            unavailableCurrencies.add(quoteCurrency);
            return null;
        }
    }

    // ── Helper: empty-portfolio sentinel ────────────────────────────────────

    private PortfolioAnalyticsDto emptyAnalytics(String baseCurrency) {
        PerformanceCoverageDto emptyCoverage = new PerformanceCoverageDto(0, 0, false, false);
        return new PortfolioAnalyticsDto(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                null,
                baseCurrency,
                false,
                SENTINEL_PERFORMER,
                SENTINEL_PERFORMER,
                List.of(),
                List.of(),
                emptyCoverage
        );
    }

    // ── Helper: user existence guard ─────────────────────────────────────────

    private void requireUserExists(String userId) {
        // portfolios.user_id is a plain VARCHAR storing the JWT subclaim (e.g. "user-001").
        // The API Gateway has already authenticated the request via JWT validation, so we
        // trust non-UUID subclaims. Only throw UserNotFoundException for UUID-format sub
        // claims that have no matching user record.
        boolean hasPortfolios = portfolioRepository.existsByUserId(userId);
        if (!hasPortfolios) {
            try {
                UUID uuid = UUID.fromString(userId);
                if (!userRepository.existsById(uuid)) {
                    throw new UserNotFoundException(userId);
                }
            } catch (IllegalArgumentException ex) {
                // Non-UUID subclaim — gateway-authenticated, no portfolios yet.
                log.debug("Non-UUID user ID: {}", userId);
            }
        }
    }
}
