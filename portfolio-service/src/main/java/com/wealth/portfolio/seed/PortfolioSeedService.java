package com.wealth.portfolio.seed;

import com.wealth.portfolio.AssetHolding;
import com.wealth.portfolio.AssetHoldingRepository;
import com.wealth.portfolio.Portfolio;
import com.wealth.portfolio.PortfolioRepository;
import com.wealth.portfolio.seed.SeedTickerRegistry.SeedTicker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Resets a user's portfolio data to the deterministic "golden state": exactly 1 portfolio,
 * 160 holdings, and 160 Postgres {@code market_prices} rows, all derived from
 * {@link SeedTickerRegistry} + {@link DeterministicPriceCalculator}.
 *
 * <p>All database operations execute inside a single transaction — a failure at any step
 * rolls the whole seed back so the pre-existing state is preserved.
 *
 * <h2>Wave 3 — Task 4.2 additions</h2>
 * <ul>
 *   <li>Each seeded holding receives a deterministic {@code avg_cost_basis} computed as
 *       {@code seedPrice × (1 + signedJitter)} where the jitter is derived from
 *       {@code (ticker, userId)} hash in the range [−20%, +20%]. This produces stable,
 *       non-trivial P&amp;L across service restarts.</li>
 *   <li>Ensures {@code market_price_history} coverage for all 160 tickers by upserting
 *       a history row at the seed timestamp for each ticker (idempotent: ON CONFLICT DO NOTHING).</li>
 * </ul>
 */
@Service
public class PortfolioSeedService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioSeedService.class);

    private static final int QUANTITY_RANGE = 50;

    /**
     * Jitter range for cost-basis relative to seed price: ±20% (400 steps of 0.1%).
     * The signed jitter formula: {@code floorMod(hash, 400) → [0,399] → centre at 200 → [-200,+199] bps}.
     */
    private static final int COST_BASIS_JITTER_RANGE = 400;
    private static final int COST_BASIS_CENTRE       = 200; // midpoint → zero jitter

    private static final String UPSERT_MARKET_PRICE_SQL = """
            INSERT INTO market_prices (ticker, current_price, quote_currency, updated_at)
            VALUES (:ticker, :price, :quoteCurrency, now())
            ON CONFLICT (ticker) DO UPDATE
              SET current_price  = EXCLUDED.current_price,
                  quote_currency = EXCLUDED.quote_currency,
                  updated_at     = EXCLUDED.updated_at
            """;

    /**
     * Upserts a single seed history row per ticker so the analytics service has at
     * least one history point per holding (enabling the 24h-change tolerance window query).
     * The anchor timestamp is 25 hours in the past so it falls within the analytics
     * 24h-reference window. ON CONFLICT DO NOTHING makes this idempotent.
     */
    private static final String UPSERT_HISTORY_SQL = """
            INSERT INTO market_price_history (ticker, quote_currency, price, observed_at)
            VALUES (:ticker, :quoteCurrency, :price, :observedAt)
            ON CONFLICT (ticker, observed_at) DO NOTHING
            """;

    private final PortfolioRepository portfolioRepository;
    private final AssetHoldingRepository assetHoldingRepository;
    private final SeedTickerRegistry registry;
    private final NamedParameterJdbcTemplate jdbc;

    public PortfolioSeedService(PortfolioRepository portfolioRepository,
                                AssetHoldingRepository assetHoldingRepository,
                                SeedTickerRegistry registry,
                                NamedParameterJdbcTemplate jdbc) {
        this.portfolioRepository = portfolioRepository;
        this.assetHoldingRepository = assetHoldingRepository;
        this.registry = registry;
        this.jdbc = jdbc;
    }

    public record SeedResult(UUID portfolioId, int holdingsInserted, int marketPricesUpserted) {}

    @Transactional
    public SeedResult seed(String userId) {
        // 1. Delete every existing portfolio for this user. orphanRemoval = true on
        //    Portfolio.holdings cascades the delete down to asset_holdings in the same
        //    transaction; the explicit flush() guarantees the DELETE runs before the
        //    subsequent INSERT so we don't hit any transient unique-constraint edges.
        List<Portfolio> existing = portfolioRepository.findByUserId(userId);
        if (!existing.isEmpty()) {
            portfolioRepository.deleteAll(existing);
            portfolioRepository.flush();
        }

        // 2. Persist one fresh portfolio, then its 160 holdings via the holdings
        //    repository. Going through AssetHoldingRepository avoids touching the
        //    package-private Portfolio.addHolding() helper from this sub-package.
        Portfolio saved = portfolioRepository.save(new Portfolio(userId));
        List<SeedTicker> seeds = registry.all();

        // Anchor timestamp used for seed history rows: 25 h ago (in the 24h window).
        Instant seedHistoryTs = Instant.now().minus(25, ChronoUnit.HOURS)
                .truncatedTo(ChronoUnit.MILLIS);

        List<AssetHolding> holdings = seeds.stream()
                .map(t -> {
                    int quantity = Math.floorMod(t.ticker().hashCode(), QUANTITY_RANGE) + 1;
                    BigDecimal seedPrice = DeterministicPriceCalculator.compute(
                            t.basePrice(), t.ticker(), userId);

                    AssetHolding holding = new AssetHolding(saved, t.ticker(), BigDecimal.valueOf(quantity));

                    // Task 4.2: deterministic cost basis — seed price ± signed jitter (±20%)
                    BigDecimal costBasis = computeDeterministicCostBasis(seedPrice, t.ticker(), userId);
                    holding.setAvgCostBasis(costBasis);
                    holding.setCostBasisCurrency(t.quoteCurrency());
                    holding.setCostBasisSource("SEED");
                    holding.setCostBasisAsOf(seedHistoryTs);

                    return holding;
                })
                .toList();
        assetHoldingRepository.saveAll(holdings);

        // 3. Upsert 160 Postgres market_prices rows in a single JDBC batch round-trip.
        SqlParameterSource[] priceBatch = seeds.stream()
                .map(t -> (SqlParameterSource) new MapSqlParameterSource()
                        .addValue("ticker", t.ticker())
                        .addValue("price", DeterministicPriceCalculator.compute(
                                t.basePrice(), t.ticker(), userId))
                        .addValue("quoteCurrency", t.quoteCurrency()))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(UPSERT_MARKET_PRICE_SQL, priceBatch);

        // 4. Task 4.2: ensure market_price_history coverage for all 160 tickers.
        //    Inserts one anchor point per ticker 25 h in the past (idempotent).
        SqlParameterSource[] historyBatch = seeds.stream()
                .map(t -> (SqlParameterSource) new MapSqlParameterSource()
                        .addValue("ticker", t.ticker())
                        .addValue("quoteCurrency", t.quoteCurrency())
                        .addValue("price", DeterministicPriceCalculator.compute(
                                t.basePrice(), t.ticker(), userId))
                        .addValue("observedAt", java.sql.Timestamp.from(seedHistoryTs)))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(UPSERT_HISTORY_SQL, historyBatch);

        log.info("Golden-state seed complete: userId={} portfolioId={} holdings={} marketPrices={} historyRows={}",
                userId, saved.getId(), seeds.size(), seeds.size(), seeds.size());
        return new SeedResult(saved.getId(), seeds.size(), seeds.size());
    }

    /**
     * Computes a deterministic, non-trivial cost basis for a holding.
     *
     * <p>Formula: {@code seedPrice × (1 + signedJitter)} where {@code signedJitter} is
     * derived from the hash of {@code (ticker + ":" + userId)} in the range [−20%, +20%).
     * This matches the {@link DeterministicPriceCalculator} style for reproducibility.
     */
    static BigDecimal computeDeterministicCostBasis(BigDecimal seedPrice, String ticker, String userId) {
        int seed = (ticker + ":" + userId).hashCode();
        // Map to [0, COST_BASIS_JITTER_RANGE) then shift by COST_BASIS_CENTRE to get signed bps
        int jitterBps = Math.floorMod(seed, COST_BASIS_JITTER_RANGE) - COST_BASIS_CENTRE;
        // jitterBps ∈ [-200, +199] → [-20.00%, +19.99%]
        BigDecimal multiplier = BigDecimal.ONE.add(BigDecimal.valueOf(jitterBps, 4));
        return seedPrice.multiply(multiplier).setScale(4, RoundingMode.HALF_UP);
    }
}
