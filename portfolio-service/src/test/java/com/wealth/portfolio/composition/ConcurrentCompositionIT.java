package com.wealth.portfolio.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wealth.catalog.SupportedCatalog;
import com.wealth.portfolio.AssetHolding;
import com.wealth.portfolio.GlobalExceptionHandler;
import com.wealth.portfolio.Portfolio;
import com.wealth.portfolio.PortfolioRepository;
import com.wealth.portfolio.TestContainerImages;
import com.wealth.portfolio.seed.SeedTickerRegistry;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Wave 4c integration proofs: child-only version transitions, concurrent CAS/named-uniqueness
 * arbitration, symmetric preparer races, no-op identity preservation, aggregate-creation timestamps,
 * monotonic {@code updated_at}, catalog-scale requests, and named quantity CHECK backstop behaviour.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(HoldingReplacementServiceIT.ClockTestConfig.class)
class ConcurrentCompositionIT {

    private static final Instant CLOCK_START = Instant.parse("2026-06-01T12:00:00Z");
    private static final Instant GOLDEN_ANCHOR = Instant.parse("2020-01-01T00:00:00Z");

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
    @Autowired GlobalExceptionHandler globalExceptionHandler;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired SupportedCatalog catalog;
    @Autowired Clock clock;

    private String activeTicker;
    private String otherActiveTicker;
    private TransactionTemplate transactionTemplate;
    private SeedTickerRegistry seedRegistry;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        ((MutableClock) clock).set(CLOCK_START);
        activeTicker = catalog.active().getFirst().ticker();
        otherActiveTicker = catalog.active().get(1).ticker();
        seedRegistry = new SeedTickerRegistry(catalog, catalog.seedView());
        jdbcTemplate.update("DELETE FROM asset_holdings");
        jdbcTemplate.update("DELETE FROM portfolios");
    }

    @Test
    void childOnlyMutationIncrementsVersionExactlyOnce() {
        String userId = userId();
        CompositionResult seeded =
                replacementService.replace(
                        userId,
                        0L,
                        List.of(new RawIntent(activeTicker, new BigDecimal("1.00000000"))),
                        compositionPreparer);
        long v = seeded.version();
        assertThat(v).isEqualTo(1L);

        CompositionResult result =
                replacementService.replace(
                        userId,
                        v,
                        List.of(new RawIntent(activeTicker, new BigDecimal("5.00000000"))),
                        compositionPreparer);

        assertThat(result.noOp()).isFalse();
        assertThat(result.version()).isEqualTo(v + 1);
        assertThat(result.version()).isNotEqualTo(v + 2);

        Portfolio reloaded =
                transactionTemplate.execute(
                        status -> {
                            Portfolio p = portfolioRepository.findByUserId(userId).getFirst();
                            p.getHoldings().size();
                            return p;
                        });
        assertThat(reloaded.getVersion()).isEqualTo(v + 1);
        assertThat(reloaded.getVersion()).isNotEqualTo(v + 2);
        assertThat(reloaded.getHoldings()).hasSize(1);
        assertThat(reloaded.getHoldings().getFirst().getQuantity())
                .isEqualByComparingTo("5.00000000");
    }

    @Test
    void concurrentPresentMutationsExactlyOneWinsParentCasRace() throws Exception {
        String userId = userId();
        CompositionResult seeded =
                replacementService.replace(
                        userId,
                        0L,
                        List.of(new RawIntent(activeTicker, new BigDecimal("1.00000000"))),
                        compositionPreparer);
        long expectedVersion = seeded.version();
        assertThat(expectedVersion).isEqualTo(1L);

        CountDownLatch arrivedAtPreparer = new CountDownLatch(2);
        CountDownLatch proceedFromPreparer = new CountDownLatch(1);
        TuplePreparer barrierPreparer =
                barrierAround(compositionPreparer, arrivedAtPreparer, proceedFromPreparer);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<CompositionResult> outcomeA = new AtomicReference<>();
        AtomicReference<Throwable> errorA = new AtomicReference<>();
        AtomicReference<CompositionResult> outcomeB = new AtomicReference<>();
        AtomicReference<Throwable> errorB = new AtomicReference<>();

        try {
            Future<?> a =
                    pool.submit(
                            () -> {
                                try {
                                    outcomeA.set(
                                            replacementService.replace(
                                                    userId,
                                                    expectedVersion,
                                                    List.of(
                                                            new RawIntent(
                                                                    activeTicker,
                                                                    new BigDecimal("10.00000000"))),
                                                    barrierPreparer));
                                } catch (Throwable t) {
                                    errorA.set(t);
                                }
                                return null;
                            });
            Future<?> b =
                    pool.submit(
                            () -> {
                                try {
                                    outcomeB.set(
                                            replacementService.replace(
                                                    userId,
                                                    expectedVersion,
                                                    List.of(
                                                            new RawIntent(
                                                                    otherActiveTicker,
                                                                    new BigDecimal("20.00000000"))),
                                                    barrierPreparer));
                                } catch (Throwable t) {
                                    errorB.set(t);
                                }
                                return null;
                            });

            assertThat(arrivedAtPreparer.await(10, TimeUnit.SECONDS)).isTrue();
            proceedFromPreparer.countDown();
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        List<CompositionResult> successes = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        if (outcomeA.get() != null) {
            successes.add(outcomeA.get());
        }
        if (errorA.get() != null) {
            failures.add(errorA.get());
        }
        if (outcomeB.get() != null) {
            successes.add(outcomeB.get());
        }
        if (errorB.get() != null) {
            failures.add(errorB.get());
        }

        assertThat(successes).hasSize(1);
        assertThat(failures).hasSize(1);
        assertThat(successes.getFirst().noOp()).isFalse();
        assertThat(successes.getFirst().version()).isEqualTo(2L);
        assertThat(failures.getFirst()).isInstanceOf(PortfolioVersionConflictException.class);

        Portfolio after =
                transactionTemplate.execute(
                        status -> {
                            Portfolio p = portfolioRepository.findByUserId(userId).getFirst();
                            p.getHoldings().size();
                            return p;
                        });
        assertThat(after.getVersion()).isEqualTo(2L);
        assertThat(after.getHoldings()).hasSize(1);
        DesiredHoldingState winnerHolding = successes.getFirst().holdings().getFirst();
        AssetHolding stored = after.getHoldings().getFirst();
        assertThat(stored.getAssetTicker()).isEqualTo(winnerHolding.ticker());
        assertThat(stored.getQuantity()).isEqualByComparingTo(winnerHolding.quantity());
        if (winnerHolding.avgCostBasis() == null) {
            assertThat(stored.getAvgCostBasis()).isNull();
        } else {
            assertThat(stored.getAvgCostBasis()).isEqualByComparingTo(winnerHolding.avgCostBasis());
        }
        assertThat(stored.getCostBasisCurrency()).isEqualTo(winnerHolding.costBasisCurrency());
        assertThat(stored.getCostBasisSource()).isEqualTo(winnerHolding.costBasisSource());
        assertThat(stored.getCostBasisAsOf()).isEqualTo(winnerHolding.costBasisAsOf());
        assertThat(winnerHolding.ticker()).isIn(activeTicker, otherActiveTicker);
    }

    @Test
    void symmetricArbitrationCompositionVsGoldenStateExactlyOneTransition() throws Exception {
        String userId = userId();
        CompositionResult seeded =
                replacementService.replace(
                        userId,
                        0L,
                        List.of(new RawIntent(activeTicker, new BigDecimal("1.00000000"))),
                        compositionPreparer);
        long expectedVersion = seeded.version();

        GoldenStateTuplePreparer goldenPreparer =
                new GoldenStateTuplePreparer(seedRegistry, userId, GOLDEN_ANCHOR);

        CountDownLatch arrivedAtPreparer = new CountDownLatch(2);
        CountDownLatch proceedFromPreparer = new CountDownLatch(1);
        AtomicInteger compositionMaterialiseCalls = new AtomicInteger();
        AtomicInteger goldenMaterialiseCalls = new AtomicInteger();
        TuplePreparer compositionBarrier =
                barrierAround(
                        compositionPreparer,
                        arrivedAtPreparer,
                        proceedFromPreparer,
                        compositionMaterialiseCalls);
        TuplePreparer goldenBarrier =
                barrierAround(
                        goldenPreparer,
                        arrivedAtPreparer,
                        proceedFromPreparer,
                        goldenMaterialiseCalls);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<CompositionResult> compositionOutcome = new AtomicReference<>();
        AtomicReference<Throwable> compositionError = new AtomicReference<>();
        AtomicReference<CompositionResult> goldenOutcome = new AtomicReference<>();
        AtomicReference<Throwable> goldenError = new AtomicReference<>();

        try {
            Future<?> compositionThread =
                    pool.submit(
                            () -> {
                                try {
                                    compositionOutcome.set(
                                            replacementService.replace(
                                                    userId,
                                                    expectedVersion,
                                                    List.of(
                                                            new RawIntent(
                                                                    activeTicker,
                                                                    new BigDecimal("10.00000000"))),
                                                    compositionBarrier));
                                } catch (Throwable t) {
                                    compositionError.set(t);
                                }
                                return null;
                            });
            Future<?> goldenThread =
                    pool.submit(
                            () -> {
                                try {
                                    goldenOutcome.set(
                                            replacementService.replace(
                                                    userId, expectedVersion, List.of(), goldenBarrier));
                                } catch (Throwable t) {
                                    goldenError.set(t);
                                }
                                return null;
                            });

            assertThat(arrivedAtPreparer.await(10, TimeUnit.SECONDS)).isTrue();
            proceedFromPreparer.countDown();
            compositionThread.get(30, TimeUnit.SECONDS);
            goldenThread.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        // Separate counters prove neither writer retries after losing CAS (latch cannot).
        assertThat(compositionMaterialiseCalls.get()).isEqualTo(1);
        assertThat(goldenMaterialiseCalls.get()).isEqualTo(1);

        int successCount =
                (compositionOutcome.get() != null ? 1 : 0) + (goldenOutcome.get() != null ? 1 : 0);
        int failureCount =
                (compositionError.get() != null ? 1 : 0) + (goldenError.get() != null ? 1 : 0);
        assertThat(successCount).isEqualTo(1);
        assertThat(failureCount).isEqualTo(1);

        CompositionResult winner =
                compositionOutcome.get() != null ? compositionOutcome.get() : goldenOutcome.get();
        Throwable loser =
                compositionError.get() != null ? compositionError.get() : goldenError.get();
        assertThat(winner.noOp()).isFalse();
        assertThat(winner.version()).isEqualTo(expectedVersion + 1);
        assertThat(loser).isInstanceOf(PortfolioVersionConflictException.class);

        Portfolio after =
                transactionTemplate.execute(
                        status -> {
                            Portfolio p = portfolioRepository.findByUserId(userId).getFirst();
                            p.getHoldings().size();
                            return p;
                        });
        assertThat(after.getVersion()).isEqualTo(expectedVersion + 1);
        assertThat(after.getHoldings()).hasSize(winner.holdings().size());
        for (DesiredHoldingState expected : winner.holdings()) {
            AssetHolding stored =
                    after.getHoldings().stream()
                            .filter(h -> h.getAssetTicker().equals(expected.ticker()))
                            .findFirst()
                            .orElseThrow();
            assertThat(stored.getQuantity()).isEqualByComparingTo(expected.quantity());
            if (expected.avgCostBasis() == null) {
                assertThat(stored.getAvgCostBasis()).isNull();
            } else {
                assertThat(stored.getAvgCostBasis()).isEqualByComparingTo(expected.avgCostBasis());
            }
            assertThat(stored.getCostBasisCurrency()).isEqualTo(expected.costBasisCurrency());
            assertThat(stored.getCostBasisSource()).isEqualTo(expected.costBasisSource());
            assertThat(stored.getCostBasisAsOf()).isEqualTo(expected.costBasisAsOf());
        }
    }

    @Test
    void concurrentAbsentCreatorsExactlyOneWinsNamedConstraintRace() throws Exception {
        String userId = userId();
        CountDownLatch arrivedAtPreparer = new CountDownLatch(2);
        CountDownLatch proceedFromPreparer = new CountDownLatch(1);
        // Barrier after both observe absence and pass expectedVersion==0 / semantic / catalog,
        // before saveAndFlush — forces the named uniqueness-race path.
        TuplePreparer barrierPreparer =
                barrierAround(compositionPreparer, arrivedAtPreparer, proceedFromPreparer);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<CompositionResult> winnerA = new AtomicReference<>();
        AtomicReference<CompositionResult> winnerB = new AtomicReference<>();
        AtomicReference<Throwable> errorA = new AtomicReference<>();
        AtomicReference<Throwable> errorB = new AtomicReference<>();

        try {
            Future<?> a =
                    pool.submit(
                            () -> {
                                try {
                                    winnerA.set(
                                            replacementService.replace(
                                                    userId, 0L, List.of(), barrierPreparer));
                                } catch (Throwable t) {
                                    errorA.set(t);
                                }
                                return null;
                            });
            Future<?> b =
                    pool.submit(
                            () -> {
                                try {
                                    winnerB.set(
                                            replacementService.replace(
                                                    userId, 0L, List.of(), barrierPreparer));
                                } catch (Throwable t) {
                                    errorB.set(t);
                                }
                                return null;
                            });

            assertThat(arrivedAtPreparer.await(10, TimeUnit.SECONDS)).isTrue();
            proceedFromPreparer.countDown();
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        List<CompositionResult> successes = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        if (winnerA.get() != null) {
            successes.add(winnerA.get());
        }
        if (errorA.get() != null) {
            failures.add(errorA.get());
        }
        if (winnerB.get() != null) {
            successes.add(winnerB.get());
        }
        if (errorB.get() != null) {
            failures.add(errorB.get());
        }

        assertThat(successes).hasSize(1);
        assertThat(failures).hasSize(1);
        assertThat(successes.getFirst().created()).isTrue();
        assertThat(successes.getFirst().version()).isEqualTo(1L);
        assertThat(failures.getFirst()).isInstanceOf(PortfolioVersionConflictException.class);
        PortfolioVersionConflictException loserConflict =
                (PortfolioVersionConflictException) failures.getFirst();
        assertThat(loserConflict.currentVersion()).isEmpty();
        assertThat(loserConflict.lookupUserId()).contains(userId);

        assertThat(portfolioRepository.findByUserId(userId)).hasSize(1);
        assertThat(countHoldings(userId)).isZero();

        ResponseEntity<ContractError> resolved =
                globalExceptionHandler.handlePortfolioVersionConflict(loserConflict);
        assertThat(resolved.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resolved.getBody()).isNotNull();
        assertThat(resolved.getBody().currentVersion()).isEqualTo(1L);
    }

    @Test
    void matchingNoOpDoesNotAdvanceVersionUpdatedAtOrChildIdentities() {
        String userId = userId();
        CompositionResult first =
                replacementService.replace(
                        userId,
                        0L,
                        List.of(new RawIntent(activeTicker, new BigDecimal("2.00000000"))),
                        compositionPreparer);
        assertThat(first.created()).isTrue();
        assertThat(first.version()).isEqualTo(1L);

        Portfolio before = portfolioRepository.findByUserId(userId).getFirst();
        Instant createdBefore = before.getCreatedAt();
        Instant updatedBefore = before.getUpdatedAt();
        long versionBefore = before.getVersion();
        List<HoldingRow> holdingsBefore = loadHoldingRows(before.getId());

        CompositionResult noop =
                replacementService.replace(
                        userId,
                        versionBefore,
                        List.of(new RawIntent(activeTicker, new BigDecimal("2.00000000"))),
                        compositionPreparer);

        assertThat(noop.noOp()).isTrue();
        assertThat(noop.version()).isEqualTo(versionBefore);

        Portfolio after = portfolioRepository.findByUserId(userId).getFirst();
        assertThat(after.getVersion()).isEqualTo(versionBefore);
        assertThat(after.getCreatedAt()).isEqualTo(createdBefore);
        assertThat(after.getUpdatedAt()).isEqualTo(updatedBefore);
        assertThat(loadHoldingRows(after.getId())).containsExactlyElementsOf(holdingsBefore);
    }

    @Test
    void absentAggregateCreationBindsNonNullTimestampsEqualToStored() {
        String userId = userId();

        CompositionResult result =
                replacementService.replace(
                        userId,
                        0L,
                        List.of(new RawIntent(activeTicker, new BigDecimal("1.00000000"))),
                        compositionPreparer);

        assertThat(result.created()).isTrue();
        assertThat(result.createdAt()).isNotNull();
        assertThat(result.updatedAt()).isNotNull();

        Portfolio stored = portfolioRepository.findByUserId(userId).getFirst();
        assertThat(stored.getCreatedAt()).isNotNull();
        assertThat(stored.getUpdatedAt()).isNotNull();
        assertThat(result.createdAt()).isEqualTo(stored.getCreatedAt());
        assertThat(result.updatedAt()).isEqualTo(stored.getUpdatedAt());
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
    void namedQuantityCheckRejectsInvalidPreparerOutputWithoutMutatingAggregate() {
        String userId = userId();
        CompositionResult seeded =
                replacementService.replace(
                        userId,
                        0L,
                        List.of(new RawIntent(activeTicker, new BigDecimal("2.00000000"))),
                        compositionPreparer);
        long versionBefore = seeded.version();
        List<HoldingRow> holdingsBefore =
                loadHoldingRows(portfolioRepository.findByUserId(userId).getFirst().getId());
        Instant updatedBefore = readUpdatedAt(userId);

        TuplePreparer zeroQuantityPreparer =
                (intent, locked) -> {
                    List<DesiredHoldingState> normal =
                            compositionPreparer.materialise(intent, locked);
                    DesiredHoldingState first = normal.getFirst();
                    return List.of(
                            new DesiredHoldingState(
                                    first.ticker(),
                                    BigDecimal.ZERO,
                                    first.avgCostBasis(),
                                    first.costBasisCurrency(),
                                    first.costBasisSource(),
                                    first.costBasisAsOf()));
                };

        assertThatThrownBy(
                        () ->
                                replacementService.replace(
                                        userId,
                                        versionBefore,
                                        List.of(
                                                new RawIntent(
                                                        activeTicker, new BigDecimal("3.00000000"))),
                                        zeroQuantityPreparer))
                .isInstanceOf(QuantityOutOfDomainException.class);

        Portfolio after = portfolioRepository.findByUserId(userId).getFirst();
        assertThat(after.getVersion()).isEqualTo(versionBefore);
        assertThat(after.getUpdatedAt()).isEqualTo(updatedBefore);
        assertThat(loadHoldingRows(after.getId())).containsExactlyElementsOf(holdingsBefore);
    }

    @Test
    void orderIrrelevantMatchingDesiredSetIsNoOp() {
        String userId = userId();
        replacementService.replace(
                userId,
                0L,
                List.of(
                        new RawIntent(activeTicker, new BigDecimal("1.00000000")),
                        new RawIntent(otherActiveTicker, new BigDecimal("2.00000000"))),
                compositionPreparer);

        Portfolio before = portfolioRepository.findByUserId(userId).getFirst();
        long versionBefore = before.getVersion();
        Instant updatedBefore = before.getUpdatedAt();
        List<HoldingRow> holdingsBefore = loadHoldingRows(before.getId());

        CompositionResult noop =
                replacementService.replace(
                        userId,
                        versionBefore,
                        List.of(
                                new RawIntent(otherActiveTicker, new BigDecimal("2.00000000")),
                                new RawIntent(activeTicker, new BigDecimal("1.00000000"))),
                        compositionPreparer);

        assertThat(noop.noOp()).isTrue();
        assertThat(noop.version()).isEqualTo(versionBefore);
        assertThat(noop.holdings())
                .extracting(DesiredHoldingState::ticker)
                .containsExactly(
                        holdingsBefore.stream().map(HoldingRow::ticker).toArray(String[]::new));
        assertThat(noop.holdings())
                .extracting(DesiredHoldingState::quantity)
                .containsExactly(
                        holdingsBefore.stream().map(HoldingRow::quantity).toArray(BigDecimal[]::new));
        Portfolio after = portfolioRepository.findByUserId(userId).getFirst();
        assertThat(after.getVersion()).isEqualTo(versionBefore);
        assertThat(after.getUpdatedAt()).isEqualTo(updatedBefore);
        assertThat(loadHoldingRows(after.getId())).containsExactlyElementsOf(holdingsBefore);
    }

    @Test
    void noFixedMaximumFullCatalogRequestSucceeds() {
        String userId = userId();
        List<RawIntent> fullCatalog =
                catalog.active().stream()
                        .map(entry -> new RawIntent(entry.ticker(), BigDecimal.ONE))
                        .toList();
        int catalogSize = catalog.active().size();
        assertThat(fullCatalog).hasSize(catalogSize);

        CompositionResult result =
                replacementService.replace(userId, 0L, fullCatalog, compositionPreparer);

        assertThat(result.created()).isTrue();
        assertThat(result.noOp()).isFalse();
        assertThat(result.holdings()).hasSize(catalogSize);
        Portfolio stored =
                transactionTemplate.execute(
                        status -> {
                            Portfolio p = portfolioRepository.findByUserId(userId).getFirst();
                            p.getHoldings().size();
                            return p;
                        });
        assertThat(stored.getHoldings()).hasSize(catalogSize);
    }

    @Test
    void emptyDesiredSetRemovesAllHoldingsWithExactVersionIncrement() {
        String userId = userId();
        CompositionResult seeded =
                replacementService.replace(
                        userId,
                        0L,
                        List.of(new RawIntent(activeTicker, new BigDecimal("1.00000000"))),
                        compositionPreparer);
        long v = seeded.version();

        CompositionResult result =
                replacementService.replace(userId, v, List.of(), compositionPreparer);

        assertThat(result.noOp()).isFalse();
        assertThat(result.version()).isEqualTo(v + 1);
        assertThat(result.holdings()).isEmpty();
        Portfolio stored = portfolioRepository.findByUserId(userId).getFirst();
        assertThat(stored.getVersion()).isEqualTo(v + 1);
        assertThat(countHoldings(userId)).isZero();

        GoldenStateTuplePreparer goldenPreparer =
                new GoldenStateTuplePreparer(seedRegistry, userId, GOLDEN_ANCHOR);
        List<DesiredHoldingState> goldenEmptyExpansion =
                goldenPreparer.materialise(List.of(), List.of());
        assertThat(goldenEmptyExpansion).hasSize(catalog.active().size());
    }

    private TuplePreparer barrierAround(
            TuplePreparer delegate,
            CountDownLatch arrivedAtPreparer,
            CountDownLatch proceedFromPreparer) {
        return barrierAround(delegate, arrivedAtPreparer, proceedFromPreparer, null);
    }

    private TuplePreparer barrierAround(
            TuplePreparer delegate,
            CountDownLatch arrivedAtPreparer,
            CountDownLatch proceedFromPreparer,
            AtomicInteger materialiseCalls) {
        return (intent, locked) -> {
            if (materialiseCalls != null) {
                materialiseCalls.incrementAndGet();
            }
            arrivedAtPreparer.countDown();
            try {
                assertThat(proceedFromPreparer.await(30, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted at CAS barrier", e);
            }
            return delegate.materialise(intent, locked);
        };
    }

    private record HoldingRow(
            UUID id,
            String ticker,
            BigDecimal quantity,
            BigDecimal avgCostBasis,
            String costBasisCurrency,
            String costBasisSource,
            Instant costBasisAsOf) {}

    private List<HoldingRow> loadHoldingRows(UUID portfolioId) {
        return jdbcTemplate.query(
                """
                SELECT id, asset_ticker, quantity, avg_cost_basis, cost_basis_currency,
                       cost_basis_source, cost_basis_as_of
                  FROM asset_holdings
                 WHERE portfolio_id = ?
                 ORDER BY asset_ticker
                """,
                (rs, rowNum) ->
                        new HoldingRow(
                                UUID.fromString(rs.getString("id")),
                                rs.getString("asset_ticker"),
                                rs.getBigDecimal("quantity"),
                                rs.getBigDecimal("avg_cost_basis"),
                                rs.getString("cost_basis_currency"),
                                rs.getString("cost_basis_source"),
                                readInstant(rs, "cost_basis_as_of")),
                portfolioId);
    }

    private static Instant readInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private int countHoldings(String userId) {
        Integer count =
                jdbcTemplate.queryForObject(
                        """
                        SELECT count(*)
                          FROM asset_holdings h
                          JOIN portfolios p ON p.id = h.portfolio_id
                         WHERE p.user_id = ?
                        """,
                        Integer.class,
                        userId);
        return count == null ? 0 : count;
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
