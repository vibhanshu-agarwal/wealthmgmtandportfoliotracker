package com.wealth.portfolio.seed;

import com.wealth.portfolio.AssetHolding;
import com.wealth.portfolio.AssetHoldingRepository;
import com.wealth.portfolio.Portfolio;
import com.wealth.portfolio.PortfolioRepository;
import com.wealth.portfolio.seed.PortfolioSeedService.SeedResult;
import com.wealth.portfolio.seed.SeedTickerRegistry.SeedTicker;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers-backed integration test for {@link PortfolioSeedService}.
 *
 * <p>Validates Requirement 12 of the Golden-State Seeder spec: the seeder produces
 * exactly 1 portfolio + 160 holdings + 160 {@code market_prices} rows, returns the
 * generated {@code portfolioId}, and is idempotent at the value level on the second
 * invocation (counts are stable, per-ticker quantities and prices are byte-identical,
 * and the previous portfolio + its cascaded holdings are cleanly removed).
 *
 * <p>Uses {@code postgres:18-alpine} to match the production Neon Postgres 18 target
 * (design doc §11). Runs as part of the {@code integrationTest} task:
 * {@code ./gradlew :portfolio-service:integrationTest --tests "*PortfolioSeedServiceIT*"}.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class PortfolioSeedServiceIT {

    private static final String E2E_USER_ID = "00000000-0000-0000-0000-000000000e2e";
    private static final int EXPECTED_HOLDINGS = 160;

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
                    .withDatabaseName("portfolio_db")
                    .withUsername("wealth_user")
                    .withPassword("wealth_pass");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Kafka isn't on the seeder's call path and KafkaAutoConfiguration would
        // need a broker during context init. Mirror the pattern used by every
        // other Testcontainers IT in this module.
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration");
    }

    @Autowired PortfolioSeedService seedService;
    @Autowired PortfolioRepository portfolioRepository;
    @Autowired AssetHoldingRepository assetHoldingRepository;
    @Autowired SeedTickerRegistry registry;
    @Autowired JdbcTemplate jdbc;

    @Test
    void seederEstablishesGoldenStateAndIsIdempotent() {
        // ── First invocation: must create 1 portfolio + 160 holdings + 160 prices ──
        SeedResult first = seedService.seed(E2E_USER_ID);

        assertThat(first.portfolioId()).as("portfolioId must be returned to the caller").isNotNull();
        assertThat(first.holdingsInserted()).isEqualTo(EXPECTED_HOLDINGS);
        assertThat(first.marketPricesUpserted()).isEqualTo(EXPECTED_HOLDINGS);

        List<Portfolio> portfoliosAfterFirst = portfolioRepository.findByUserId(E2E_USER_ID);
        assertThat(portfoliosAfterFirst).hasSize(1);
        assertThat(portfoliosAfterFirst.get(0).getId()).isEqualTo(first.portfolioId());

        Portfolio portfolioFirst = portfoliosAfterFirst.get(0);
        List<AssetHolding> holdingsFirst = assetHoldingRepository.findByPortfolio(portfolioFirst);
        assertThat(holdingsFirst).hasSize(EXPECTED_HOLDINGS);

        Set<String> registryTickers = registry.all().stream()
                .map(SeedTicker::ticker).collect(Collectors.toSet());
        Set<String> holdingTickers = holdingsFirst.stream()
                .map(AssetHolding::getAssetTicker).collect(Collectors.toSet());
        assertThat(holdingTickers).isEqualTo(registryTickers);

        // Every registry ticker must have exactly one market_prices row after the upsert.
        // V2 already seeds some of these tickers; the upsert should leave total matches == 160.
        Long marketPriceCount = jdbc.queryForObject(
                "SELECT count(*) FROM market_prices WHERE ticker IN ("
                        + placeholders(registryTickers.size()) + ")",
                Long.class, registryTickers.toArray());
        assertThat(marketPriceCount).isEqualTo((long) EXPECTED_HOLDINGS);

        Map<String, BigDecimal> quantitiesFirst = holdingsFirst.stream()
                .collect(Collectors.toUnmodifiableMap(
                        AssetHolding::getAssetTicker, AssetHolding::getQuantity));
        Map<String, BigDecimal> pricesFirst = snapshotPricesByTicker();

        // ── Second invocation: counts unchanged, prior portfolio row gone, new one present ──
        SeedResult second = seedService.seed(E2E_USER_ID);

        List<Portfolio> portfoliosAfterSecond = portfolioRepository.findByUserId(E2E_USER_ID);
        assertThat(portfoliosAfterSecond).hasSize(1);
        UUID secondPortfolioId = portfoliosAfterSecond.get(0).getId();
        assertThat(secondPortfolioId).isEqualTo(second.portfolioId());
        // Delete-and-replace semantics: the first portfolio must be gone.
        assertThat(portfolioRepository.findById(first.portfolioId())).isEmpty();

        List<AssetHolding> holdingsSecond = assetHoldingRepository.findByPortfolio(portfoliosAfterSecond.get(0));
        assertThat(holdingsSecond).hasSize(EXPECTED_HOLDINGS);

        // ── Determinism: quantities and prices are byte-identical per ticker ──
        Map<String, BigDecimal> quantitiesSecond = holdingsSecond.stream()
                .collect(Collectors.toUnmodifiableMap(
                        AssetHolding::getAssetTicker, AssetHolding::getQuantity));
        assertThat(quantitiesSecond).containsExactlyInAnyOrderEntriesOf(quantitiesFirst);

        Map<String, BigDecimal> pricesSecond = snapshotPricesByTicker();
        assertThat(pricesSecond.keySet()).isEqualTo(pricesFirst.keySet());
        pricesFirst.forEach((ticker, expected) ->
                assertThat(pricesSecond.get(ticker))
                        .as("deterministic price for %s", ticker)
                        .isEqualByComparingTo(expected));
    }

    private Map<String, BigDecimal> snapshotPricesByTicker() {
        List<SeedTicker> all = registry.all();
        return jdbc.query(
                "SELECT ticker, current_price FROM market_prices WHERE ticker IN ("
                        + placeholders(all.size()) + ")",
                ps -> {
                    int i = 1;
                    for (SeedTicker t : all) ps.setString(i++, t.ticker());
                },
                rs -> {
                    Map<String, BigDecimal> out = new java.util.HashMap<>();
                    while (rs.next()) out.put(rs.getString(1), rs.getBigDecimal(2));
                    return out;
                });
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }
}
