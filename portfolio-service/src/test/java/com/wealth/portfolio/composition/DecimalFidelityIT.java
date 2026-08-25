package com.wealth.portfolio.composition;

import static org.assertj.core.api.Assertions.assertThat;

import com.wealth.catalog.SupportedCatalog;
import com.wealth.portfolio.AssetHolding;
import com.wealth.portfolio.Portfolio;
import com.wealth.portfolio.PortfolioRepository;
import com.wealth.portfolio.PortfolioResponse;
import com.wealth.portfolio.TestContainerImages;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Candidate decimal-fidelity suite ({@code *DecimalFidelityIT}): PostgreSQL NUMERIC(19,8) round-trip
 * and no-op equality on the persisted representation. Strict JSON token rejection remains in {@link
 * StrictDecimalFidelityTest}.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(HoldingReplacementServiceIT.ClockTestConfig.class)
class DecimalFidelityIT {

    private static final Instant CLOCK_START = Instant.parse("2026-06-01T12:00:00Z");

    @Container
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(TestContainerImages.POSTGRES)
                    .withDatabaseName("portfolio_db")
                    .withUsername("wealth_user")
                    .withPassword("wealth_pass");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired HoldingReplacementService replacementService;
    @Autowired CompositionTuplePreparer compositionPreparer;
    @Autowired PortfolioRepository portfolioRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired SupportedCatalog catalog;
    @Autowired Clock clock;

    private String activeTicker;
    private TransactionTemplate transactionTemplate;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        ((MutableClock) clock).set(CLOCK_START);
        activeTicker = catalog.active().getFirst().ticker();
        jdbcTemplate.update("DELETE FROM asset_holdings");
        jdbcTemplate.update("DELETE FROM portfolios");
    }

    @Test
    void persistedQuantityRoundTripsAsByteIdenticalJsonString() throws Exception {
        String userId = UUID.randomUUID().toString();

        CompositionResult created =
                replacementService.replace(
                        userId,
                        0L,
                        List.of(new RawIntent(activeTicker, new BigDecimal("0.75000000"))),
                        compositionPreparer);
        assertThat(created.noOp()).isFalse();

        Portfolio reloaded =
                transactionTemplate.execute(
                        status -> {
                            Portfolio p = portfolioRepository.findByUserId(userId).getFirst();
                            p.getHoldings().size();
                            return p;
                        });
        AssetHolding held = reloaded.getHoldings().getFirst();
        assertThat(held.getQuantity()).isEqualByComparingTo("0.75000000");

        PortfolioResponse.HoldingResponse responseHolding =
                new PortfolioResponse.HoldingResponse(
                        held.getId(), held.getAssetTicker(), held.getQuantity());
        String json = mapper.writeValueAsString(responseHolding);
        assertThat(json).contains("\"quantity\":\"0.75000000\"");
        assertThat(json).doesNotContain("\"quantity\":0.75");
        assertThat(json).doesNotContain("\"quantity\":0.75000000");
    }

    @Test
    void scaleMismatchedIntentAgainstPersistedNumericIsNoOp() {
        String userId = UUID.randomUUID().toString();

        CompositionResult seeded =
                replacementService.replace(
                        userId,
                        0L,
                        List.of(new RawIntent(activeTicker, new BigDecimal("0.75000000"))),
                        compositionPreparer);
        assertThat(seeded.version()).isEqualTo(1L);

        Portfolio mid = portfolioRepository.findByUserId(userId).getFirst();
        Instant updatedBefore = mid.getUpdatedAt();
        UUID holdingId =
                transactionTemplate.execute(
                        status ->
                                portfolioRepository
                                        .findByUserId(userId)
                                        .getFirst()
                                        .getHoldings()
                                        .getFirst()
                                        .getId());

        CompositionResult noop =
                replacementService.replace(
                        userId,
                        1L,
                        List.of(new RawIntent(activeTicker, new BigDecimal("0.75"))),
                        compositionPreparer);

        assertThat(noop.noOp()).isTrue();
        assertThat(noop.version()).isEqualTo(1L);
        Portfolio after = portfolioRepository.findByUserId(userId).getFirst();
        assertThat(after.getVersion()).isEqualTo(1L);
        assertThat(after.getUpdatedAt()).isEqualTo(updatedBefore);

        AssetHolding held =
                transactionTemplate.execute(
                        status -> {
                            AssetHolding h =
                                    portfolioRepository
                                            .findByUserId(userId)
                                            .getFirst()
                                            .getHoldings()
                                            .getFirst();
                            h.getQuantity();
                            return h;
                        });
        assertThat(held.getId()).isEqualTo(holdingId);
        assertThat(held.getQuantity()).isEqualByComparingTo("0.75000000");
        assertThat(noop.holdings().getFirst().quantity().toPlainString()).isEqualTo("0.75000000");
        assertThat(noop.holdings().getFirst().quantity().scale()).isEqualTo(8);
    }
}
