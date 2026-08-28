package com.wealth.portfolio.seed;

import com.wealth.portfolio.AssetHolding;
import com.wealth.portfolio.AssetHoldingRepository;
import com.wealth.portfolio.Portfolio;
import com.wealth.portfolio.PortfolioRepository;
import com.wealth.portfolio.seed.diag.SpecA912StartupTransactionDiagnostics;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Converges the demo account's portfolio to the deterministic desired state derived from
 * the active catalog. Shipped gated off ({@code app.demo.seed-on-startup=false}); activated
 * at cutover checkpoint 9.12.
 *
 * <p>When the gate is false this runner returns immediately — no lock, no read, no compare.
 * When {@code APP_DEMO_TX_DIAGNOSTICS=true} with the gate still false, an initializer-shaped
 * rollback probe runs instead (no {@link PortfolioSeedService#seed(String)}). Both flags true
 * fails closed before any database work.
 */
@Component
public class DemoPortfolioInitializer implements ApplicationRunner {

    /**
     * Showcase demo user from {@code V15__Reconcile_Auth_Seed_Users.sql}. Hard-coded: a
     * configurable demo user is one typo away from seeding a real account.
     */
    public static final String DEMO_USER_ID = "00000000-0000-0000-0000-0000000d3110";

    /**
     * Transaction-scoped advisory lock key. CRC32 of UTF-8
     * {@code "com.wealth.portfolio.seed.demo-portfolio"} (unsigned). Fixed across replicas;
     * not derived from hostname, catalog, or user id.
     */
    public static final long ADVISORY_LOCK_KEY = 4014252457L;

    private static final Logger log = LoggerFactory.getLogger(DemoPortfolioInitializer.class);

    public enum Outcome { SEEDED, CONVERGED }

    private final DemoProperties demoProperties;
    private final EntityManager entityManager;
    private final PortfolioRepository portfolioRepository;
    private final AssetHoldingRepository assetHoldingRepository;
    private final PortfolioSeedService seedService;
    private final TransactionTemplate transactionTemplate;
    private final SpecA912StartupTransactionDiagnostics txDiagnostics;
    private final String replicaId;

    public DemoPortfolioInitializer(DemoProperties demoProperties,
                                    EntityManager entityManager,
                                    PortfolioRepository portfolioRepository,
                                    AssetHoldingRepository assetHoldingRepository,
                                    PortfolioSeedService seedService,
                                    PlatformTransactionManager transactionManager,
                                    SpecA912StartupTransactionDiagnostics txDiagnostics) {
        this.demoProperties = demoProperties;
        this.entityManager = entityManager;
        this.portfolioRepository = portfolioRepository;
        this.assetHoldingRepository = assetHoldingRepository;
        this.seedService = seedService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.txDiagnostics = txDiagnostics;
        this.replicaId = resolveReplicaId();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (SpecA912StartupTransactionDiagnostics.rejectBothFlags(demoProperties.seedOnStartup())) {
            log.error(
                    "event=spec_a912_tx_diag_rejected reason=APP_DEMO_TX_DIAGNOSTICS and seed-on-startup both enabled");
            return;
        }

        txDiagnostics.captureSpring("run-entry", null);

        if (SpecA912StartupTransactionDiagnostics.diagnosticsOnly(demoProperties.seedOnStartup())) {
            runRollbackProbe();
            return;
        }

        if (!demoProperties.seedOnStartup()) {
            return;
        }

        converge();
    }

    /**
     * Initializer-shaped, non-mutating rollback probe for Spec A 9.12 RCA. Never calls
     * {@link PortfolioSeedService#seed(String)}; probe failures are logged and swallowed.
     */
    void runRollbackProbe() {
        txDiagnostics.captureSpring("probe-before-transaction-template", null);
        try {
            SpecA912StartupTransactionDiagnostics.DmlProbeOutcome dmlOutcome =
                    transactionTemplate.execute(
                            status -> {
                                txDiagnostics.captureSpring("probe-inside-transaction-template", status);
                                acquireLock();
                                txDiagnostics.captureJdbcInTransaction("probe-after-advisory-lock");
                                List<Portfolio> portfolios = portfolioRepository.findByUserId(DEMO_USER_ID);
                                txDiagnostics.captureJdbcInTransaction("probe-after-findByUserId");
                                portfolios.stream()
                                        .findFirst()
                                        .ifPresent(assetHoldingRepository::findByPortfolio);
                                txDiagnostics.captureJdbcInTransaction("probe-after-findByPortfolio");
                                seedService.desiredHoldings(DEMO_USER_ID);
                                txDiagnostics.captureJdbcInTransaction("probe-after-desiredHoldings");
                                SpecA912StartupTransactionDiagnostics.DmlProbeOutcome outcome =
                                        txDiagnostics.runDmlProbe("probe-before-dml-probe");
                                status.setRollbackOnly();
                                return outcome;
                            });
            log.info(
                    "event=spec_a912_tx_probe_complete replica={} dmlProbeOutcome={} transactionRollbackOnly=true",
                    replicaId,
                    dmlOutcome != null ? dmlOutcome : SpecA912StartupTransactionDiagnostics.DmlProbeOutcome.SKIPPED);
        } catch (RuntimeException ex) {
            log.error(
                    "event=spec_a912_tx_probe_failed replica={} cause={}: {}",
                    replicaId,
                    ex.getClass().getSimpleName(),
                    ex.getMessage());
        }
    }

    /**
     * Compare-then-maybe-seed under {@code pg_advisory_xact_lock}. Safe to call from tests
     * with the gate off; {@link #run} is the only production entry that honours the gate.
     */
    public Outcome converge() {
        return transactionTemplate.execute(status -> convergeInTransaction());
    }

    Outcome convergeInTransaction() {
        acquireLock();
        List<Portfolio> portfolios = portfolioRepository.findByUserId(DEMO_USER_ID);
        List<PortfolioSeedService.DesiredHolding> desired = seedService.desiredHoldings(DEMO_USER_ID);
        if (portfolios.size() == 1
                && matchesDesired(assetHoldingRepository.findByPortfolio(portfolios.get(0)), desired)) {
            log.info("event=demo_portfolio_converged replica={} userId={}", replicaId, DEMO_USER_ID);
            return Outcome.CONVERGED;
        }
        seedService.seed(DEMO_USER_ID);
        log.info("event=demo_portfolio_seeded replica={} userId={}", replicaId, DEMO_USER_ID);
        return Outcome.SEEDED;
    }

    private void acquireLock() {
        Session session = entityManager.unwrap(Session.class);
        session.doWork(this::pgAdvisoryXactLock);
    }

    private void pgAdvisoryXactLock(java.sql.Connection connection) throws java.sql.SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
            ps.setLong(1, ADVISORY_LOCK_KEY);
            ps.execute();
        }
    }

    static boolean matchesDesired(List<AssetHolding> actual,
                                  List<PortfolioSeedService.DesiredHolding> desired) {
        Set<String> desiredTickers = desired.stream()
                .map(PortfolioSeedService.DesiredHolding::ticker)
                .collect(Collectors.toUnmodifiableSet());
        Set<String> actualTickers = actual.stream()
                .map(AssetHolding::getAssetTicker)
                .collect(Collectors.toSet());
        if (!actualTickers.equals(desiredTickers)) {
            return false;
        }
        Map<String, PortfolioSeedService.DesiredHolding> byTicker = desired.stream()
                .collect(Collectors.toUnmodifiableMap(
                        PortfolioSeedService.DesiredHolding::ticker, d -> d));
        for (AssetHolding holding : actual) {
            PortfolioSeedService.DesiredHolding expected = byTicker.get(holding.getAssetTicker());
            if (!holdingMatches(holding, expected)) {
                return false;
            }
        }
        return true;
    }

    private static boolean holdingMatches(AssetHolding actual,
                                          PortfolioSeedService.DesiredHolding expected) {
        return actual.getQuantity() != null
                && actual.getQuantity().compareTo(expected.quantity()) == 0
                && actual.getAvgCostBasis() != null
                && actual.getAvgCostBasis().compareTo(expected.avgCostBasis()) == 0
                && Objects.equals(expected.costBasisCurrency(), actual.getCostBasisCurrency())
                && "SEED".equals(actual.getCostBasisSource())
                && actual.getCostBasisAsOf() != null
                && actual.getCostBasisAsOf().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                        .equals(expected.costBasisAsOf().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    static String resolveReplicaId() {
        String replica = System.getenv("CONTAINER_APP_REPLICA_NAME");
        if (replica == null || replica.isBlank()) {
            replica = System.getenv("HOSTNAME");
        }
        if (replica == null || replica.isBlank()) {
            replica = "unknown";
        }
        return replica;
    }
}
