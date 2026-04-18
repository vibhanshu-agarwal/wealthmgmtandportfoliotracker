package com.wealth.portfolio;

import com.wealth.portfolio.dto.PortfolioAnalyticsDto;
import com.wealth.portfolio.dto.PortfolioAnalyticsDto.HoldingAnalyticsDto;
import com.wealth.portfolio.dto.PortfolioAnalyticsDto.PerformerDto;
import com.wealth.portfolio.dto.PortfolioAnalyticsDto.PerformancePointDto;
import com.wealth.portfolio.fx.FxProperties;
import com.wealth.user.UserRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes the full portfolio analytics payload for {@code GET /api/portfolio/analytics}.
 *
 * <p>Uses a single SQL round-trip (CTE + UNION ALL) to retrieve holdings, 24h-ago prices,
 * and historical series data. FX conversion is applied per holding. Results are cached
 * per-user with a profile-appropriate backend (Caffeine locally, Redis on AWS).
 */
@Service
public class PortfolioAnalyticsService {
    private static final Logger log = org.slf4j.LoggerFactory.getLogger(PortfolioAnalyticsService.class);

    private static final int DEFAULT_PERIOD_DAYS = 50;
    private static final int SYNTHETIC_THRESHOLD = 7;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final PerformerDto SENTINEL_PERFORMER = new PerformerDto("N/A", BigDecimal.ZERO);

    /**
     * Single SQL query returning two row types:
     * <ul>
     *   <li>HOLDING — one row per holding with the current price and the closest 24h-ago price.</li>
     *   <li>HISTORY — one row per (ticker, date) within the requested period.</li>
     * </ul>
     */
    private static final String ANALYTICS_SQL = """
            WITH user_tickers AS (
                SELECT h.asset_ticker,
                       h.quantity,
                       COALESCE(mp.current_price, 0)      AS current_price,
                       COALESCE(mp.quote_currency, 'USD') AS quote_currency
                FROM asset_holdings h
                JOIN portfolios p ON p.id = h.portfolio_id
                LEFT JOIN market_prices mp ON mp.ticker = h.asset_ticker
                WHERE p.user_id = ?
            ),
            price_24h AS (
                SELECT DISTINCT ON (mph.ticker)
                       mph.ticker,
                       mph.price AS price_24h_ago
                FROM market_price_history mph
                JOIN user_tickers ut ON ut.asset_ticker = mph.ticker
                WHERE mph.observed_at <= now() - INTERVAL '24 hours'
                ORDER BY mph.ticker, mph.observed_at DESC
            )
            SELECT 'HOLDING'             AS row_type,
                   ut.asset_ticker,
                   ut.quantity,
                   ut.current_price,
                   ut.quote_currency,
                   p24.price_24h_ago,
                   NULL::DATE            AS history_date,
                   NULL::NUMERIC         AS history_price
            FROM user_tickers ut
            LEFT JOIN price_24h p24 ON p24.ticker = ut.asset_ticker
            UNION ALL
            SELECT 'HISTORY'             AS row_type,
                   mph.ticker            AS asset_ticker,
                   NULL::NUMERIC         AS quantity,
                   NULL::NUMERIC         AS current_price,
                   ut.quote_currency,
                   NULL::NUMERIC         AS price_24h_ago,
                   mph.observed_at::DATE AS history_date,
                   mph.price             AS history_price
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

    public PortfolioAnalyticsService(JdbcTemplate jdbcTemplate,
                                     UserRepository userRepository,
                                     PortfolioRepository portfolioRepository,
                                     FxRateProvider fxRateProvider,
                                     FxProperties fxProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.userRepository = userRepository;
        this.portfolioRepository = portfolioRepository;
        this.fxRateProvider = fxRateProvider;
        this.fxProperties = fxProperties;
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
                (rs, i) -> new AnalyticsQueryRow(
                        rs.getString("row_type"),
                        rs.getString("asset_ticker"),
                        rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("current_price"),
                        rs.getString("quote_currency"),
                        rs.getBigDecimal("price_24h_ago"),
                        rs.getString("history_date"),
                        rs.getBigDecimal("history_price")
                ),
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

        // ── Per-holding computation ──────────────────────────────────────────
        List<HoldingAnalyticsDto> holdingDtos = new ArrayList<>();
        for (AnalyticsQueryRow row : holdingRows) {
            BigDecimal rate = fxRate(row.quoteCurrency(), baseCurrency, fxRateCache);
            BigDecimal currentValueBase = row.quantity()
                    .multiply(row.currentPrice())
                    .multiply(rate)
                    .setScale(4, RoundingMode.HALF_UP);

            // Placeholder: avgCostBasis = currentPrice until trade ledger is available
            BigDecimal avgCostBasis = row.currentPrice();
            BigDecimal costBasisBase = row.quantity()
                    .multiply(avgCostBasis)
                    .multiply(rate)
                    .setScale(4, RoundingMode.HALF_UP);
            BigDecimal unrealizedPnL = currentValueBase.subtract(costBasisBase);

            BigDecimal change24hAbs = computeChange24hAbsolute(row.currentPrice(), row.price24hAgo());
            BigDecimal change24hPct = computeChange24hPercent(row.currentPrice(), row.price24hAgo());

            holdingDtos.add(new HoldingAnalyticsDto(
                    row.assetTicker(),
                    row.quantity(),
                    row.currentPrice(),
                    currentValueBase,
                    avgCostBasis,
                    unrealizedPnL,
                    change24hAbs,
                    change24hPct,
                    row.quoteCurrency()
            ));
        }

        // ── Aggregates ───────────────────────────────────────────────────────
        BigDecimal totalValue = holdingDtos.stream()
                .map(HoldingAnalyticsDto::currentValueBase)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCostBasis = holdingDtos.stream()
                .map(h -> {
                    BigDecimal rate = fxRate(h.quoteCurrency(), baseCurrency, fxRateCache);
                    return h.quantity().multiply(h.avgCostBasis()).multiply(rate)
                            .setScale(4, RoundingMode.HALF_UP);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalPnL = totalValue.subtract(totalCostBasis);
        BigDecimal totalPnLPct = totalCostBasis.compareTo(BigDecimal.ZERO) > 0
                ? totalPnL.divide(totalCostBasis, 4, RoundingMode.HALF_UP).multiply(HUNDRED)
                : BigDecimal.ZERO;

        PerformerDto best = holdingDtos.stream()
                .max(Comparator.comparing(HoldingAnalyticsDto::change24hPercent))
                .map(h -> new PerformerDto(h.ticker(), h.change24hPercent()))
                .orElse(SENTINEL_PERFORMER);

        PerformerDto worst = holdingDtos.stream()
                .min(Comparator.comparing(HoldingAnalyticsDto::change24hPercent))
                .map(h -> new PerformerDto(h.ticker(), h.change24hPercent()))
                .orElse(SENTINEL_PERFORMER);

        // ── Performance series ───────────────────────────────────────────────
        List<PerformancePointDto> series = buildPerformanceSeries(
                historyRows, holdingRows, totalValue, baseCurrency, fxRateCache);

        return new PortfolioAnalyticsDto(
                totalValue,
                totalCostBasis,
                totalPnL,
                totalPnLPct,
                baseCurrency,
                best,
                worst,
                holdingDtos,
                series
        );
    }

    // ── Helper: 24h change ───────────────────────────────────────────────────

    BigDecimal computeChange24hPercent(BigDecimal currentPrice, BigDecimal price24hAgo) {
        if (price24hAgo == null || price24hAgo.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return currentPrice.subtract(price24hAgo)
                .divide(price24hAgo, new MathContext(10, RoundingMode.HALF_UP))
                .multiply(HUNDRED)
                .setScale(4, RoundingMode.HALF_UP);
    }

    BigDecimal computeChange24hAbsolute(BigDecimal currentPrice, BigDecimal price24hAgo) {
        if (price24hAgo == null) {
            return BigDecimal.ZERO;
        }
        return currentPrice.subtract(price24hAgo).setScale(4, RoundingMode.HALF_UP);
    }

    // ── Helper: performance series ───────────────────────────────────────────

    List<PerformancePointDto> buildPerformanceSeries(
            List<AnalyticsQueryRow> historyRows,
            List<AnalyticsQueryRow> holdingRows,
            BigDecimal totalValue,
            String baseCurrency,
            Map<String, BigDecimal> fxRateCache) {

        // Group history rows by date
        Map<String, List<AnalyticsQueryRow>> byDate = historyRows.stream()
                .filter(r -> r.historyDate() != null)
                .collect(Collectors.groupingBy(AnalyticsQueryRow::historyDate));

        if (byDate.size() < SYNTHETIC_THRESHOLD) {
            return generateSyntheticSeries(totalValue, SYNTHETIC_THRESHOLD);
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
                    BigDecimal rate = fxRate(histRow.quoteCurrency(), baseCurrency, fxRateCache);
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

        return series;
    }

    /**
     * Generates a synthetic {@code days}-point performance series ending at {@code anchorValue}.
     * Used as a local-dev fallback when real history data has fewer than 7 distinct dates.
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

    // ── Helper: FX rate with per-request cache ───────────────────────────────

    private BigDecimal fxRate(String quoteCurrency, String baseCurrency,
                              Map<String, BigDecimal> cache) {
        if (quoteCurrency.equals(baseCurrency)) {
            return BigDecimal.ONE;
        }
        return cache.computeIfAbsent(quoteCurrency,
                qc -> fxRateProvider.getRate(qc, baseCurrency));
    }

    // ── Helper: empty-portfolio sentinel ────────────────────────────────────

    private PortfolioAnalyticsDto emptyAnalytics(String baseCurrency) {
        return new PortfolioAnalyticsDto(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                baseCurrency,
                SENTINEL_PERFORMER,
                SENTINEL_PERFORMER,
                List.of(),
                List.of()
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
