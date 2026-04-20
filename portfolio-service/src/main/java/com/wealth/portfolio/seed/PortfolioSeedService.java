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
import java.util.List;
import java.util.UUID;

/**
 * Resets a user's portfolio data to the deterministic "golden state": exactly 1 portfolio,
 * 160 holdings, and 160 Postgres {@code market_prices} rows, all derived from
 * {@link SeedTickerRegistry} + {@link DeterministicPriceCalculator}.
 *
 * <p>All database operations execute inside a single transaction — a failure at any step
 * rolls the whole seed back so the pre-existing state is preserved.
 */
@Service
public class PortfolioSeedService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioSeedService.class);

    private static final int QUANTITY_RANGE = 50;

    private static final String UPSERT_MARKET_PRICE_SQL = """
            INSERT INTO market_prices (ticker, current_price, quote_currency, updated_at)
            VALUES (:ticker, :price, :quoteCurrency, now())
            ON CONFLICT (ticker) DO UPDATE
              SET current_price  = EXCLUDED.current_price,
                  quote_currency = EXCLUDED.quote_currency,
                  updated_at     = EXCLUDED.updated_at
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
        List<AssetHolding> holdings = seeds.stream()
                .map(t -> {
                    int quantity = Math.floorMod(t.ticker().hashCode(), QUANTITY_RANGE) + 1;
                    return new AssetHolding(saved, t.ticker(), BigDecimal.valueOf(quantity));
                })
                .toList();
        assetHoldingRepository.saveAll(holdings);

        // 3. Upsert 160 Postgres market_prices rows in a single JDBC batch round-trip.
        SqlParameterSource[] batch = seeds.stream()
                .map(t -> (SqlParameterSource) new MapSqlParameterSource()
                        .addValue("ticker", t.ticker())
                        .addValue("price", DeterministicPriceCalculator.compute(
                                t.basePrice(), t.ticker(), userId))
                        .addValue("quoteCurrency", t.quoteCurrency()))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(UPSERT_MARKET_PRICE_SQL, batch);

        log.info("Golden-state seed complete: userId={} portfolioId={} holdings={} marketPrices={}",
                userId, saved.getId(), seeds.size(), seeds.size());
        return new SeedResult(saved.getId(), seeds.size(), seeds.size());
    }
}
