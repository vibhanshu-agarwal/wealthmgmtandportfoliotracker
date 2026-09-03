package com.wealth.portfolio.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

import com.wealth.catalog.SupportedCatalog;
import com.wealth.portfolio.AssetHolding;
import com.wealth.portfolio.Portfolio;
import com.wealth.portfolio.PortfolioRepository;
import com.wealth.portfolio.PortfolioResponse;
import com.wealth.portfolio.PortfolioService;
import com.wealth.portfolio.TestContainerImages;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL proof that a projection disagreement inside {@link CompositionWriteService} rolls back
 * the entire replacement transaction — version, timestamps, holding identities, and cost basis.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class CompositionWriteServiceIT {

    @Container
    @SuppressWarnings("resource")
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

    @MockitoSpyBean PortfolioService portfolioService;

    @Autowired CompositionWriteService writeService;
    @Autowired PortfolioRepository portfolioRepository;
    @Autowired SupportedCatalog catalog;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private String activeTicker;
    private String otherActiveTicker;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        activeTicker = catalog.active().getFirst().ticker();
        otherActiveTicker = catalog.active().get(1).ticker();
        jdbcTemplate.update("DELETE FROM asset_holdings");
        jdbcTemplate.update("DELETE FROM portfolios");
    }

    @AfterEach
    void restoreProjection() {
        doCallRealMethod().when(portfolioService).toPortfolioResponse(any(Portfolio.class));
    }

    @Test
    void projectionMismatchRollsBackVersionTimestampsHoldingsAndCostBasis() {
        String userId = UUID.randomUUID().toString();
        Instant asOf = Instant.parse("2025-06-01T00:00:00Z");
        UUID portfolioId = seedTwoHoldingsWithCostBasis(userId, asOf);
        Portfolio before = snapshot(userId);
        UUID keepHoldingId =
                before.getHoldings().stream()
                        .filter(h -> h.getAssetTicker().equals(activeTicker))
                        .map(AssetHolding::getId)
                        .findFirst()
                        .orElseThrow();
        UUID removeHoldingId =
                before.getHoldings().stream()
                        .filter(h -> h.getAssetTicker().equals(otherActiveTicker))
                        .map(AssetHolding::getId)
                        .findFirst()
                        .orElseThrow();
        Instant updatedAt = before.getUpdatedAt();
        Instant createdAt = before.getCreatedAt();

        doAnswer(
                        invocation -> {
                            PortfolioResponse real =
                                    (PortfolioResponse) invocation.callRealMethod();
                            return new PortfolioResponse(
                                    real.id(),
                                    real.userId(),
                                    real.createdAt(),
                                    real.updatedAt(),
                                    real.version() + 1L,
                                    real.holdings());
                        })
                .when(portfolioService)
                .toPortfolioResponse(any(Portfolio.class));

        CompositionHoldingsRequest request =
                new CompositionHoldingsRequest(
                        0L,
                        List.of(
                                new CompositionHoldingsRequest.HoldingIntent(
                                        activeTicker, new BigDecimal("9.00000000"))));

        assertThatThrownBy(() -> writeService.replace(userId, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disagrees");

        Portfolio after = snapshot(userId);
        assertThat(after.getId()).isEqualTo(portfolioId);
        assertThat(after.getVersion()).isEqualTo(0L);
        assertThat(after.getCreatedAt()).isEqualTo(createdAt);
        assertThat(after.getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(after.getHoldings()).hasSize(2);

        AssetHolding keep =
                after.getHoldings().stream()
                        .filter(h -> h.getAssetTicker().equals(activeTicker))
                        .findFirst()
                        .orElseThrow();
        AssetHolding remove =
                after.getHoldings().stream()
                        .filter(h -> h.getAssetTicker().equals(otherActiveTicker))
                        .findFirst()
                        .orElseThrow();

        assertThat(keep.getId()).isEqualTo(keepHoldingId);
        assertThat(keep.getQuantity()).isEqualByComparingTo("1.00000000");
        assertThat(keep.getAvgCostBasis()).isEqualByComparingTo("123.4500");
        assertThat(keep.getCostBasisCurrency()).isEqualTo("USD");
        assertThat(keep.getCostBasisSource()).isEqualTo("ADD_TIME");
        assertThat(keep.getCostBasisAsOf()).isEqualTo(asOf);

        assertThat(remove.getId()).isEqualTo(removeHoldingId);
        assertThat(remove.getQuantity()).isEqualByComparingTo("2.00000000");
        assertThat(remove.getAvgCostBasis()).isEqualByComparingTo("50.0000");
        assertThat(remove.getCostBasisCurrency()).isEqualTo("USD");
        assertThat(remove.getCostBasisSource()).isEqualTo("SEED");
        assertThat(remove.getCostBasisAsOf()).isEqualTo(asOf);
    }

    private UUID seedTwoHoldingsWithCostBasis(String userId, Instant asOf) {
        return tx.execute(
                status -> {
                    Portfolio p = portfolioRepository.saveAndFlush(new Portfolio(userId));
                    AssetHolding keep =
                            new AssetHolding(p, activeTicker, new BigDecimal("1.00000000"));
                    keep.setAvgCostBasis(new BigDecimal("123.4500"));
                    keep.setCostBasisCurrency("USD");
                    keep.setCostBasisSource("ADD_TIME");
                    keep.setCostBasisAsOf(asOf);
                    AssetHolding remove =
                            new AssetHolding(p, otherActiveTicker, new BigDecimal("2.00000000"));
                    remove.setAvgCostBasis(new BigDecimal("50.0000"));
                    remove.setCostBasisCurrency("USD");
                    remove.setCostBasisSource("SEED");
                    remove.setCostBasisAsOf(asOf);
                    p.addHolding(keep);
                    p.addHolding(remove);
                    portfolioRepository.saveAndFlush(p);
                    return p.getId();
                });
    }

    private Portfolio snapshot(String userId) {
        return tx.execute(
                status -> {
                    Portfolio p = portfolioRepository.findByUserId(userId).getFirst();
                    p.getHoldings().size();
                    p.getHoldings()
                            .forEach(
                                    h -> {
                                        h.getQuantity();
                                        h.getAvgCostBasis();
                                        h.getCostBasisCurrency();
                                        h.getCostBasisSource();
                                        h.getCostBasisAsOf();
                                    });
                    return p;
                });
    }
}
