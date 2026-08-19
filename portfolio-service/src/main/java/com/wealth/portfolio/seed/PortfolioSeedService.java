package com.wealth.portfolio.seed;

import com.wealth.portfolio.AssetHolding;
import com.wealth.portfolio.AssetHoldingRepository;
import com.wealth.portfolio.Portfolio;
import com.wealth.portfolio.PortfolioRepository;
import com.wealth.portfolio.catalog.SupportedAssetValidator;
import com.wealth.portfolio.seed.SeedTickerRegistry.SeedTicker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Resets a user's portfolio data to the deterministic "golden state": exactly 1 portfolio
 * and one holding per catalogue ticker, derived from {@link SeedTickerRegistry} +
 * {@link DeterministicPriceCalculator}.
 *
 * <p>All database operations execute inside a single transaction — a failure at any step
 * rolls the whole seed back so the pre-existing state is preserved.
 *
 * <h2>This seeder never writes market data</h2>
 *
 * <p>It previously upserted one {@code market_prices} row and one {@code market_price_history}
 * row per ticker, using synthetic values derived from {@code basePrice} and the seeded user's
 * id. Both tables are <strong>global</strong> — keyed by ticker with no user scoping — so
 * seeding any user overwrote the live refreshed price of every ticker for every user, with an
 * unconditional {@code ON CONFLICT DO UPDATE} that had no freshness guard.
 *
 * <p>That mattered because this service's seed endpoint is reachable in production and is
 * invoked there on a schedule, so real prices were being replaced by synthetic ones daily.
 * Market data is owned by {@code market-data-service}; {@code portfolio-service} writes
 * portfolios and holdings only, in every profile. Do not reintroduce a price write here.
 *
 * <h2>Cost basis</h2>
 * <ul>
 *   <li>Each seeded holding receives a deterministic {@code avg_cost_basis} computed as
 *       {@code seedPrice × (1 + signedJitter)} where the jitter is derived from
 *       {@code (ticker, userId)} hash in the range [−20%, +20%]. This produces stable,
 *       non-trivial P&amp;L across service restarts.</li>
 *   <li>The seed price feeding that calculation is computed in-memory from the catalogue's
 *       {@code basePrice} and is never persisted to {@code market_prices}.</li>
 *   <li>{@code cost_basis_as_of} is the configured {@code app.demo.cost-basis-anchor}, not
 *       {@code Instant.now()}, so a complete desired-state comparison can converge.</li>
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

    private final PortfolioRepository portfolioRepository;
    private final AssetHoldingRepository assetHoldingRepository;
    private final SeedTickerRegistry registry;
    private final SupportedAssetValidator supportedAssetValidator;
    private final DemoProperties demoProperties;

    public PortfolioSeedService(PortfolioRepository portfolioRepository,
                                AssetHoldingRepository assetHoldingRepository,
                                SeedTickerRegistry registry,
                                SupportedAssetValidator supportedAssetValidator,
                                DemoProperties demoProperties) {
        this.portfolioRepository = portfolioRepository;
        this.assetHoldingRepository = assetHoldingRepository;
        this.registry = registry;
        this.supportedAssetValidator = supportedAssetValidator;
        this.demoProperties = demoProperties;
    }

    public record SeedResult(UUID portfolioId, int holdingsInserted) {}

    /**
     * The complete desired holding tuple for {@code userId}: a pure function of
     * {@code (active catalog, userId, app.demo.cost-basis-anchor)}.
     */
    public record DesiredHolding(
            String ticker,
            BigDecimal quantity,
            BigDecimal avgCostBasis,
            String costBasisCurrency,
            String costBasisSource,
            Instant costBasisAsOf) {}

    /**
     * Computes the holdings {@link #seed(String)} would persist, without writing.
     */
    public List<DesiredHolding> desiredHoldings(String userId) {
        Instant costBasisAsOf = demoProperties.costBasisAnchor();
        return registry.active().stream()
                .map(t -> toDesiredHolding(t, userId, costBasisAsOf))
                .toList();
    }

    @Transactional
    public SeedResult seed(String userId) {
        // 1. Delete every existing portfolio for this user. orphanRemoval = true on
        //    Portfolio.holdings cascades the delete down to asset_holdings in the same
        //    transaction; the explicit flush() guarantees the DELETE runs before the
        //    subsequent INSERT so we don't hit any transient unique-constraint edges.
        List<SeedTicker> seeds = registry.active();
        for (SeedTicker ticker : seeds) {
            supportedAssetValidator.requireActive(ticker.ticker());
        }

        List<Portfolio> existing = portfolioRepository.findByUserId(userId);
        if (!existing.isEmpty()) {
            portfolioRepository.deleteAll(existing);
            portfolioRepository.flush();
        }

        // 2. Persist one fresh portfolio, then one holding per active catalogue ticker.
        //    Going through AssetHoldingRepository avoids touching the
        //    package-private Portfolio.addHolding() helper from this sub-package.
        Portfolio saved = portfolioRepository.save(new Portfolio(userId));

        List<DesiredHolding> desired = desiredHoldings(userId);
        List<AssetHolding> holdings = desired.stream()
                .map(d -> {
                    AssetHolding holding = new AssetHolding(saved, d.ticker(), d.quantity());
                    holding.setAvgCostBasis(d.avgCostBasis());
                    holding.setCostBasisCurrency(d.costBasisCurrency());
                    holding.setCostBasisSource(d.costBasisSource());
                    holding.setCostBasisAsOf(d.costBasisAsOf());
                    return holding;
                })
                .toList();
        assetHoldingRepository.saveAll(holdings);

        // No step 3. `market_prices` and `market_price_history` are global, owned by
        // market-data-service, and are deliberately not written here — see the class javadoc.
        log.info("Golden-state seed complete (holdings only): userId={} portfolioId={} holdings={}",
                userId, saved.getId(), seeds.size());
        return new SeedResult(saved.getId(), seeds.size());
    }

    private DesiredHolding toDesiredHolding(SeedTicker t, String userId, Instant costBasisAsOf) {
        int quantity = Math.floorMod(t.ticker().hashCode(), QUANTITY_RANGE) + 1;
        BigDecimal seedPrice = DeterministicPriceCalculator.compute(
                t.basePrice(), t.ticker(), userId);
        BigDecimal costBasis = computeDeterministicCostBasis(seedPrice, t.ticker(), userId);
        return new DesiredHolding(
                t.ticker(),
                BigDecimal.valueOf(quantity),
                costBasis,
                t.quoteCurrency(),
                "SEED",
                costBasisAsOf);
    }

    /**
     * Computes a deterministic, non-trivial cost basis for a holding.
     *
     * <p>Formula: {@code seedPrice × (1 + signedJitter)} where {@code signedJitter} is
     * derived from the hash of {@code (ticker + ":" + userId)} in the range [−20%, +20%).
     * This matches the {@link DeterministicPriceCalculator} style for reproducibility.
     */
    public static BigDecimal computeDeterministicCostBasis(BigDecimal seedPrice, String ticker, String userId) {
        int seed = (ticker + ":" + userId).hashCode();
        // Map to [0, COST_BASIS_JITTER_RANGE) then shift by COST_BASIS_CENTRE to get signed bps
        int jitterBps = Math.floorMod(seed, COST_BASIS_JITTER_RANGE) - COST_BASIS_CENTRE;
        // jitterBps ∈ [-200, +199] → [-20.00%, +19.99%]
        BigDecimal multiplier = BigDecimal.ONE.add(BigDecimal.valueOf(jitterBps, 4));
        return seedPrice.multiply(multiplier).setScale(4, RoundingMode.HALF_UP);
    }
}
