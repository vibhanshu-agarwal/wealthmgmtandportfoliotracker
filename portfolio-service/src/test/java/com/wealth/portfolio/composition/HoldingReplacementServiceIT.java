package com.wealth.portfolio.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wealth.catalog.SupportedCatalog;
import com.wealth.portfolio.AssetHolding;
import com.wealth.portfolio.Portfolio;
import com.wealth.portfolio.PortfolioRepository;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Testcontainers proofs for Wave 4a tasks 4.1 and 4.4: CAS/version behaviour, absent-aggregate
 * creation, cost-basis retention, and semantic/catalog rejection on absent users.
 *
 * <p>Concurrency, monotonic {@code updated_at}, no-op identity, and catalog-scale cases live in
 * {@link ConcurrentCompositionIT}.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(HoldingReplacementServiceIT.ClockTestConfig.class)
class HoldingReplacementServiceIT {

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

    @TestConfiguration
    static class ClockTestConfig {
        static final MutableClock CLOCK = new MutableClock(CLOCK_START);

        @Bean
        @Primary
        Clock testCompositionClock() {
            return CLOCK;
        }
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

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        ((MutableClock) clock).set(CLOCK_START);
        activeTicker = catalog.active().getFirst().ticker();
        jdbcTemplate.update("DELETE FROM asset_holdings");
        jdbcTemplate.update("DELETE FROM portfolios");
    }

    @Test
    void presentMutationIncrementsVersionExactlyOnceAndPersistsChildren() {
        String userId = UUID.randomUUID().toString();
        Portfolio created =
                transactionTemplate.execute(
                        status -> portfolioRepository.saveAndFlush(new Portfolio(userId)));
        assertThat(created.getVersion()).isZero();

        CompositionResult result =
                replacementService.replace(
                        userId,
                        0L,
                        List.of(new RawIntent(activeTicker, new BigDecimal("1.50000000"))),
                        compositionPreparer);

        assertThat(result.created()).isFalse();
        assertThat(result.noOp()).isFalse();
        assertThat(result.version()).isEqualTo(1L);
        assertThat(result.holdings()).hasSize(1);

        Portfolio reloaded =
                transactionTemplate.execute(
                        status -> {
                            Portfolio p = portfolioRepository.findByUserId(userId).getFirst();
                            p.getHoldings().size();
                            return p;
                        });
        assertThat(reloaded.getVersion()).isEqualTo(1L);
        assertThat(reloaded.getHoldings()).hasSize(1);
        assertThat(reloaded.getHoldings().getFirst().getQuantity())
                .isEqualByComparingTo("1.50000000");
        assertThat(reloaded.getUpdatedAt()).isAfter(reloaded.getCreatedAt());
    }

    @Test
    void absentAggregateCreatesAtVersionOneEvenWithEmptyDesiredSet() {
        String userId = UUID.randomUUID().toString();

        CompositionResult result =
                replacementService.replace(userId, 0L, List.of(), compositionPreparer);

        assertThat(result.created()).isTrue();
        assertThat(result.noOp()).isFalse();
        assertThat(result.version()).isEqualTo(1L);
        assertThat(result.holdings()).isEmpty();
        assertThat(portfolioRepository.findByUserId(userId)).hasSize(1);
        assertThat(portfolioRepository.findByUserId(userId).getFirst().getVersion()).isEqualTo(1L);
    }

    @Test
    void absentAggregateRejectsNonZeroExpectedVersionWithoutInsert() {
        String userId = UUID.randomUUID().toString();

        assertThatThrownBy(
                        () ->
                                replacementService.replace(
                                        userId,
                                        4L,
                                        List.of(
                                                new RawIntent(
                                                        activeTicker, new BigDecimal("1"))),
                                        compositionPreparer))
                .isInstanceOf(PortfolioVersionConflictException.class)
                .satisfies(
                        ex ->
                                assertThat(
                                                ((PortfolioVersionConflictException) ex)
                                                        .currentVersion())
                                        .hasValue(0L));

        assertThat(portfolioRepository.findByUserId(userId)).isEmpty();
    }

    @Test
    void retainedCostBasisSurvivesQuantityChange() {
        String userId = UUID.randomUUID().toString();
        Instant asOf = Instant.parse("2025-06-01T00:00:00Z");
        transactionTemplate.executeWithoutResult(
                status -> {
                    Portfolio p = portfolioRepository.saveAndFlush(new Portfolio(userId));
                    AssetHolding h =
                            new AssetHolding(p, activeTicker, new BigDecimal("1.00000000"));
                    h.setAvgCostBasis(new BigDecimal("123.4500"));
                    h.setCostBasisCurrency("USD");
                    h.setCostBasisSource("ADD_TIME");
                    h.setCostBasisAsOf(asOf);
                    p.addHolding(h);
                    portfolioRepository.saveAndFlush(p);
                });

        CompositionResult result =
                replacementService.replace(
                        userId,
                        0L,
                        List.of(new RawIntent(activeTicker, new BigDecimal("9.00000000"))),
                        compositionPreparer);

        assertThat(result.noOp()).isFalse();
        AssetHolding held =
                transactionTemplate.execute(
                        status -> {
                            AssetHolding h =
                                    portfolioRepository
                                            .findByUserId(userId)
                                            .getFirst()
                                            .getHoldings()
                                            .getFirst();
                            h.getAvgCostBasis();
                            return h;
                        });
        assertThat(held.getQuantity()).isEqualByComparingTo("9.00000000");
        assertThat(held.getAvgCostBasis()).isEqualByComparingTo("123.4500");
        assertThat(held.getCostBasisSource()).isEqualTo("ADD_TIME");
        assertThat(held.getCostBasisAsOf()).isEqualTo(asOf);
    }

    @Test
    void invalidDesiredSetLeavesAbsentUserWithoutBarePortfolio() {
        String userId = UUID.randomUUID().toString();

        assertThatThrownBy(
                        () ->
                                replacementService.replace(
                                        userId,
                                        0L,
                                        List.of(
                                                new RawIntent(
                                                        "NOT_IN_CATALOG", new BigDecimal("1"))),
                                        compositionPreparer))
                .isInstanceOf(UnsupportedAssetsException.class);

        assertThat(portfolioRepository.findByUserId(userId)).isEmpty();
    }
}
