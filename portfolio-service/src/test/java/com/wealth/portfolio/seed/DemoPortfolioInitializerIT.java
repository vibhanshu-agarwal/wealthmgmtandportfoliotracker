package com.wealth.portfolio.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.wealth.portfolio.AssetHolding;
import com.wealth.portfolio.AssetHoldingRepository;
import com.wealth.portfolio.Portfolio;
import com.wealth.portfolio.PortfolioRepository;
import com.wealth.portfolio.TestContainerImages;
import com.wealth.portfolio.seed.DemoPortfolioInitializer.Outcome;
import com.wealth.portfolio.seed.PortfolioSeedService.DesiredHolding;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.hibernate.Session;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
 * Testcontainers-backed integration tests for {@link DemoPortfolioInitializer}.
 *
 * <p>The initializer is shipped gated off; these tests invoke {@link DemoPortfolioInitializer#converge()}
 * directly so the default {@code app.demo.seed-on-startup=false} still proves context load is inert.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DemoPortfolioInitializerIT {

    private static final String E2E_USER_ID = "00000000-0000-0000-0000-000000000e2e";

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

    @Autowired DemoPortfolioInitializer initializer;
    @Autowired PortfolioSeedService seedService;
    @Autowired PortfolioRepository portfolioRepository;
    @Autowired AssetHoldingRepository assetHoldingRepository;
    @Autowired SeedTickerRegistry registry;
    @Autowired DemoProperties demoProperties;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    @Order(1)
    void contextLoadWithDefaultGate_doesNotReseedDemoPortfolio() {
        List<Portfolio> demo = portfolioRepository.findByUserId(DemoPortfolioInitializer.DEMO_USER_ID);
        assertThat(demo).as("V15 assigns the showcase portfolio to the demo user").hasSize(1);
        List<AssetHolding> holdings = assetHoldingRepository.findByPortfolio(demo.get(0));
        assertThat(holdings)
                .as("default seed-on-startup=false must leave the Flyway-seeded demo holdings untouched")
                .hasSize(3);
        assertThat(holdings).hasSizeLessThan(registry.active().size());
    }

    @Test
    @Order(2)
    void converge_whenDemoAbsent_seedsActiveSetAndSecondCallIsNoOp() {
        deleteUserPortfolios(DemoPortfolioInitializer.DEMO_USER_ID);

        Outcome first = initializer.converge();
        assertThat(first).isEqualTo(Outcome.SEEDED);
        assertDemoMatchesDesired();

        UUID portfolioId = portfolioRepository.findByUserId(DemoPortfolioInitializer.DEMO_USER_ID)
                .get(0).getId();

        Outcome second = initializer.converge();
        assertThat(second).isEqualTo(Outcome.CONVERGED);
        assertThat(portfolioRepository.findByUserId(DemoPortfolioInitializer.DEMO_USER_ID))
                .singleElement()
                .extracting(Portfolio::getId)
                .isEqualTo(portfolioId);
        assertDemoMatchesDesired();
    }

    @Test
    @Order(3)
    void converge_whenOneHoldingWrong_reseeds() {
        initializer.converge();
        UUID before = portfolioRepository.findByUserId(DemoPortfolioInitializer.DEMO_USER_ID)
                .get(0).getId();
        jdbc.update("""
                UPDATE asset_holdings
                   SET quantity = quantity + 1
                 WHERE portfolio_id = ?::uuid
                   AND asset_ticker = (
                        SELECT asset_ticker FROM asset_holdings
                         WHERE portfolio_id = ?::uuid
                         ORDER BY asset_ticker
                         LIMIT 1)
                """,
                before.toString(), before.toString());

        Portfolio beforeEntity = portfolioRepository.findById(before).orElseThrow();
        long versionBefore = beforeEntity.getVersion();
        java.time.Instant createdAtBefore = beforeEntity.getCreatedAt();

        Outcome outcome = initializer.converge();
        assertThat(outcome).isEqualTo(Outcome.SEEDED);

        // The corrective reseed replaces holdings in place. The former implementation deleted
        // the parent and recreated it, so this assertion was previously `isEmpty()` — the exact
        // identity churn Task 6.2 removes.
        Portfolio afterEntity = portfolioRepository.findById(before).orElseThrow();
        assertThat(afterEntity.getId()).isEqualTo(before);
        assertThat(afterEntity.getCreatedAt())
                .as("createdAt must survive a corrective reseed")
                .isEqualTo(createdAtBefore);
        assertThat(afterEntity.getVersion())
                .as("exactly one version transition for one corrective reseed")
                .isEqualTo(versionBefore + 1);
        assertThat(portfolioRepository.findByUserId(DemoPortfolioInitializer.DEMO_USER_ID))
                .as("no second aggregate is created")
                .singleElement()
                .extracting(Portfolio::getId)
                .isEqualTo(before);
        assertDemoMatchesDesired();
    }

    @Test
    @Order(4)
    void e2eSeedLeavesDemoUnchanged_andDemoSeedLeavesE2eUnchanged() {
        initializer.converge();
        List<Map<String, Object>> demoBefore = snapshotHoldings(DemoPortfolioInitializer.DEMO_USER_ID);

        long e2eObservedVersion = portfolioRepository.findByUserId(E2E_USER_ID).stream()
                .findFirst()
                .map(Portfolio::getVersion)
                .orElse(0L);
        seedService.seed(E2E_USER_ID, e2eObservedVersion);
        assertThat(snapshotHoldings(DemoPortfolioInitializer.DEMO_USER_ID))
                .as("E2E seed must not touch the demo portfolio")
                .isEqualTo(demoBefore);

        List<Map<String, Object>> e2eBefore = snapshotHoldings(E2E_USER_ID);
        initializer.converge();
        assertThat(snapshotHoldings(E2E_USER_ID))
                .as("demo converge must not touch the E2E portfolio")
                .isEqualTo(e2eBefore);
    }

    @Test
    @Order(5)
    void advisoryLockIsHeldOnTheConnectionThatWritesHoldings() {
        deleteUserPortfolios(DemoPortfolioInitializer.DEMO_USER_ID);
        AtomicBoolean lockHeldOnWriteConnection = new AtomicBoolean(false);
        AtomicBoolean secondConnectionBlocked = new AtomicBoolean(false);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Outcome outcome = initializer.convergeInTransaction();
            assertThat(outcome).isEqualTo(Outcome.SEEDED);
            assertThat(portfolioRepository.findByUserId(DemoPortfolioInitializer.DEMO_USER_ID))
                    .as("seed writes must be visible in the same transaction as the lock")
                    .hasSize(1);

            entityManager.unwrap(Session.class).doWork(connection -> {
                try (PreparedStatement ps = connection.prepareStatement("""
                        SELECT EXISTS (
                          SELECT 1 FROM pg_locks
                           WHERE locktype = 'advisory'
                             AND objsubid = 1
                             AND pid = pg_backend_pid()
                             AND granted
                             AND ((classid::bigint << 32) + objid::bigint) = ?
                        )
                        """)) {
                    ps.setLong(1, DemoPortfolioInitializer.ADVISORY_LOCK_KEY);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        lockHeldOnWriteConnection.set(rs.getBoolean(1));
                    }
                }
                try {
                    secondConnectionBlocked.set(!tryAdvisoryLockOnNewConnection());
                } catch (Exception ex) {
                    throw new java.sql.SQLException("second-connection lock probe failed", ex);
                }
            });
            status.setRollbackOnly();
        });

        assertThat(lockHeldOnWriteConnection)
                .as("pg_advisory_xact_lock must be held on the same backend pid that wrote holdings")
                .isTrue();
        assertThat(secondConnectionBlocked)
                .as("a second connection's pg_try_advisory_xact_lock must return false during seed")
                .isTrue();
        assertThat(portfolioRepository.findByUserId(DemoPortfolioInitializer.DEMO_USER_ID))
                .as("rolling back the outer transaction must undo seed writes (REQUIRED, not REQUIRES_NEW)")
                .isEmpty();
    }

    @Test
    @Order(6)
    void concurrentConverge_exactlyOneSeedAndRestConverged() throws Exception {
        deleteUserPortfolios(DemoPortfolioInitializer.DEMO_USER_ID);
        int n = 3;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await(30, TimeUnit.SECONDS);
                    return initializer.converge();
                }));
            }
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            assertThat(outcomes)
                    .containsExactlyInAnyOrder(Outcome.SEEDED, Outcome.CONVERGED, Outcome.CONVERGED);
            assertDemoMatchesDesired();
        } finally {
            pool.shutdownNow();
        }
    }

    private boolean tryAdvisoryLockOnNewConnection() throws Exception {
        try (Connection other = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            other.setAutoCommit(false);
            try (PreparedStatement ps = other.prepareStatement(
                    "SELECT pg_try_advisory_xact_lock(?)")) {
                ps.setLong(1, DemoPortfolioInitializer.ADVISORY_LOCK_KEY);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getBoolean(1);
                }
            } finally {
                other.rollback();
            }
        }
    }

    private void assertDemoMatchesDesired() {
        List<Portfolio> portfolios =
                portfolioRepository.findByUserId(DemoPortfolioInitializer.DEMO_USER_ID);
        assertThat(portfolios).hasSize(1);
        List<AssetHolding> holdings = assetHoldingRepository.findByPortfolio(portfolios.get(0));
        List<DesiredHolding> desired = seedService.desiredHoldings(DemoPortfolioInitializer.DEMO_USER_ID);
        assertThat(holdings).hasSize(desired.size());
        Set<String> desiredTickers = desired.stream().map(DesiredHolding::ticker).collect(Collectors.toSet());
        Set<String> actualTickers = holdings.stream().map(AssetHolding::getAssetTicker).collect(Collectors.toSet());
        assertThat(actualTickers).isEqualTo(desiredTickers);
        assertThat(DemoPortfolioInitializer.matchesDesired(holdings, desired)).isTrue();
        assertThat(holdings)
                .allSatisfy(h -> {
                    assertThat(h.getCostBasisSource()).isEqualTo("SEED");
                    assertThat(h.getCostBasisAsOf()).isEqualTo(demoProperties.costBasisAnchor());
                });
    }

    private void deleteUserPortfolios(String userId) {
        List<Portfolio> existing = portfolioRepository.findByUserId(userId);
        if (!existing.isEmpty()) {
            portfolioRepository.deleteAll(existing);
            portfolioRepository.flush();
        }
    }

    private List<Map<String, Object>> snapshotHoldings(String userId) {
        return jdbc.queryForList(
                """
                SELECT h.asset_ticker, h.quantity, h.avg_cost_basis, h.cost_basis_currency,
                       h.cost_basis_source, h.cost_basis_as_of
                  FROM asset_holdings h
                  JOIN portfolios p ON p.id = h.portfolio_id
                 WHERE p.user_id = ?
                 ORDER BY h.asset_ticker
                """,
                userId);
    }
}
