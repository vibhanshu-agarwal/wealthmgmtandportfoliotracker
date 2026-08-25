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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
 * creation, named uniqueness-race arbitration, present-aggregate CAS race, and monotonic
 * {@code updated_at}.
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
    private String otherActiveTicker;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        ((MutableClock) clock).set(CLOCK_START);
        activeTicker = catalog.active().getFirst().ticker();
        otherActiveTicker = catalog.active().get(1).ticker();
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
    void matchingNoOpDoesNotAdvanceVersionOrUpdatedAt() {
        String userId = UUID.randomUUID().toString();

        CompositionResult first =
                replacementService.replace(
                        userId,
                        0L,
                        List.of(new RawIntent(activeTicker, new BigDecimal("2.00000000"))),
                        compositionPreparer);
        assertThat(first.created()).isTrue();
        assertThat(first.version()).isEqualTo(1L);

        Portfolio mid = portfolioRepository.findByUserId(userId).getFirst();
        Instant updatedBefore = mid.getUpdatedAt();

        CompositionResult noop =
                replacementService.replace(
                        userId,
                        1L,
                        List.of(new RawIntent(activeTicker, new BigDecimal("2.00000000"))),
                        compositionPreparer);

        assertThat(noop.noOp()).isTrue();
        assertThat(noop.version()).isEqualTo(1L);
        Portfolio after = portfolioRepository.findByUserId(userId).getFirst();
        assertThat(after.getVersion()).isEqualTo(1L);
        assertThat(after.getUpdatedAt()).isEqualTo(updatedBefore);
    }

    @Test
    void equalClockStillAdvancesUpdatedAtByOneMicrosecond() {
        String userId = seedPresentAggregateAtVersionZero(userId());
        Instant pinned = Instant.parse("2026-06-15T08:00:00Z");
        pinUpdatedAt(userId, pinned);
        Instant oldUpdatedAt = readUpdatedAt(userId);
        ((MutableClock) clock).set(oldUpdatedAt);

        CompositionResult result =
                replacementService.replace(
                        userId,
                        0L,
                        List.of(new RawIntent(activeTicker, new BigDecimal("3.00000000"))),
                        compositionPreparer);

        assertThat(result.noOp()).isFalse();
        assertThat(result.version()).isEqualTo(1L);
        assertThat(result.updatedAt()).isEqualTo(oldUpdatedAt.plus(1, ChronoUnit.MICROS));
        assertThat(result.updatedAt()).isAfter(oldUpdatedAt);
    }

    @Test
    void regressedClockStillAdvancesUpdatedAtStrictly() {
        String userId = seedPresentAggregateAtVersionZero(userId());
        Instant pinned = Instant.parse("2026-06-15T08:00:00Z");
        pinUpdatedAt(userId, pinned);
        Instant oldUpdatedAt = readUpdatedAt(userId);
        ((MutableClock) clock).set(oldUpdatedAt.minus(2, ChronoUnit.HOURS));

        CompositionResult result =
                replacementService.replace(
                        userId,
                        0L,
                        List.of(new RawIntent(activeTicker, new BigDecimal("4.00000000"))),
                        compositionPreparer);

        assertThat(result.noOp()).isFalse();
        assertThat(result.version()).isEqualTo(1L);
        assertThat(result.updatedAt()).isEqualTo(oldUpdatedAt.plus(1, ChronoUnit.MICROS));
        assertThat(result.updatedAt()).isAfter(oldUpdatedAt);
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
    void concurrentAbsentCreatorsExactlyOneWinsNamedConstraintRace() throws Exception {
        String userId = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<CompositionResult> winner = new AtomicReference<>();
        AtomicReference<Throwable> loser = new AtomicReference<>();

        Future<?> a =
                pool.submit(
                        () -> {
                            ready.countDown();
                            start.await();
                            try {
                                CompositionResult r =
                                        replacementService.replace(
                                                userId, 0L, List.of(), compositionPreparer);
                                winner.compareAndSet(null, r);
                            } catch (Throwable t) {
                                loser.compareAndSet(null, t);
                            }
                            return null;
                        });
        Future<?> b =
                pool.submit(
                        () -> {
                            ready.countDown();
                            start.await();
                            try {
                                CompositionResult r =
                                        replacementService.replace(
                                                userId, 0L, List.of(), compositionPreparer);
                                winner.compareAndSet(null, r);
                            } catch (Throwable t) {
                                loser.compareAndSet(null, t);
                            }
                            return null;
                        });

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        a.get(30, TimeUnit.SECONDS);
        b.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(winner.get()).isNotNull();
        assertThat(winner.get().created()).isTrue();
        assertThat(winner.get().version()).isEqualTo(1L);
        assertThat(loser.get()).isInstanceOf(PortfolioVersionConflictException.class);
        assertThat(((PortfolioVersionConflictException) loser.get()).currentVersion()).isEmpty();
        assertThat(portfolioRepository.findByUserId(userId)).hasSize(1);
    }

    @Test
    void concurrentPresentMutationsExactlyOneWinsParentCasRace() throws Exception {
        String userId = UUID.randomUUID().toString();
        CompositionResult seeded =
                replacementService.replace(
                        userId,
                        0L,
                        List.of(new RawIntent(activeTicker, new BigDecimal("1.00000000"))),
                        compositionPreparer);
        assertThat(seeded.version()).isEqualTo(1L);
        long expectedVersion = 1L;

        CountDownLatch arrivedAtPreparer = new CountDownLatch(2);
        CountDownLatch proceedFromPreparer = new CountDownLatch(1);
        TuplePreparer barrierPreparer =
                (intent, locked) -> {
                    arrivedAtPreparer.countDown();
                    try {
                        assertThat(proceedFromPreparer.await(30, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted at CAS barrier", e);
                    }
                    return compositionPreparer.materialise(intent, locked);
                };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<CompositionResult> success = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<BigDecimal> winnerQuantity = new AtomicReference<>();

        Future<?> a =
                pool.submit(
                        () -> {
                            try {
                                CompositionResult r =
                                        replacementService.replace(
                                                userId,
                                                expectedVersion,
                                                List.of(
                                                        new RawIntent(
                                                                activeTicker,
                                                                new BigDecimal("10.00000000"))),
                                                barrierPreparer);
                                if (success.compareAndSet(null, r)) {
                                    winnerQuantity.set(new BigDecimal("10.00000000"));
                                }
                            } catch (Throwable t) {
                                failure.compareAndSet(null, t);
                            }
                            return null;
                        });
        Future<?> b =
                pool.submit(
                        () -> {
                            try {
                                CompositionResult r =
                                        replacementService.replace(
                                                userId,
                                                expectedVersion,
                                                List.of(
                                                        new RawIntent(
                                                                otherActiveTicker,
                                                                new BigDecimal("20.00000000"))),
                                                barrierPreparer);
                                if (success.compareAndSet(null, r)) {
                                    winnerQuantity.set(new BigDecimal("20.00000000"));
                                }
                            } catch (Throwable t) {
                                failure.compareAndSet(null, t);
                            }
                            return null;
                        });

        assertThat(arrivedAtPreparer.await(10, TimeUnit.SECONDS)).isTrue();
        proceedFromPreparer.countDown();
        a.get(30, TimeUnit.SECONDS);
        b.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(success.get()).isNotNull();
        assertThat(success.get().noOp()).isFalse();
        assertThat(success.get().version()).isEqualTo(2L);
        assertThat(failure.get()).isInstanceOf(PortfolioVersionConflictException.class);

        Portfolio after =
                transactionTemplate.execute(
                        status -> {
                            Portfolio p = portfolioRepository.findByUserId(userId).getFirst();
                            p.getHoldings().size();
                            return p;
                        });
        assertThat(after.getVersion()).isEqualTo(2L);
        assertThat(after.getHoldings()).hasSize(1);
        assertThat(after.getHoldings().getFirst().getQuantity())
                .isEqualByComparingTo(winnerQuantity.get());
        String winnerTicker = after.getHoldings().getFirst().getAssetTicker();
        assertThat(winnerTicker).isIn(activeTicker, otherActiveTicker);
        if (winnerQuantity.get().compareTo(new BigDecimal("10.00000000")) == 0) {
            assertThat(winnerTicker).isEqualTo(activeTicker);
        } else {
            assertThat(winnerTicker).isEqualTo(otherActiveTicker);
        }
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

    private static String userId() {
        return UUID.randomUUID().toString();
    }

    private String seedPresentAggregateAtVersionZero(String userId) {
        transactionTemplate.executeWithoutResult(
                status -> portfolioRepository.saveAndFlush(new Portfolio(userId)));
        return userId;
    }

    private void pinUpdatedAt(String userId, Instant updatedAt) {
        int rows =
                jdbcTemplate.update(
                        "UPDATE portfolios SET updated_at = ? WHERE user_id = ?",
                        LocalDateTime.ofInstant(updatedAt, ZoneOffset.UTC),
                        userId);
        assertThat(rows).isEqualTo(1);
    }

    private Instant readUpdatedAt(String userId) {
        return portfolioRepository.findByUserId(userId).getFirst().getUpdatedAt();
    }
}
