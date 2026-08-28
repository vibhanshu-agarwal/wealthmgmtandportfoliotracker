package com.wealth.portfolio.seed;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wealth.portfolio.AssetHolding;
import com.wealth.portfolio.AssetHoldingRepository;
import com.wealth.portfolio.Portfolio;
import com.wealth.portfolio.PortfolioRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.hibernate.jdbc.Work;
import org.junit.jupiter.api.AfterEach;
import com.wealth.portfolio.seed.diag.SpecA912StartupTransactionDiagnostics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DemoPortfolioInitializerTest {

    private static final Instant ANCHOR = Instant.parse("2019-06-15T12:30:00Z");
    private static final PortfolioSeedService.DesiredHolding AAPL_DESIRED =
            new PortfolioSeedService.DesiredHolding(
                    "AAPL",
                    BigDecimal.TEN,
                    new BigDecimal("152.0000"),
                    "USD",
                    "SEED",
                    ANCHOR);

    @Mock private EntityManager entityManager;
    @Mock private Session session;
    @Mock private Connection connection;
    @Mock private PreparedStatement preparedStatement;
    @Mock private PortfolioRepository portfolioRepository;
    @Mock private AssetHoldingRepository assetHoldingRepository;
    @Mock private PortfolioSeedService seedService;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger targetLogger;

    @BeforeEach
    void setUpLockStubs() throws Exception {
        lenient().when(entityManager.unwrap(Session.class)).thenReturn(session);
        lenient().doAnswer(invocation -> {
            Work work = invocation.getArgument(0);
            lenient().when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
            work.execute(connection);
            return null;
        }).when(session).doWork(any());

        targetLogger = (Logger) LoggerFactory.getLogger(DemoPortfolioInitializer.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        targetLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        targetLogger.detachAppender(logAppender);
    }

    @Test
    void run_whenGateOff_doesNotTouchTheDatabase() {
        DemoPortfolioInitializer initializer = initializer(false);

        initializer.run(new DefaultApplicationArguments());

        verify(entityManager, never()).unwrap(any());
        verify(portfolioRepository, never()).findByUserId(any());
        verify(assetHoldingRepository, never()).findByPortfolio(any());
        verify(seedService, never()).seed(any());
        verify(seedService, never()).desiredHoldings(any());
    }

    @Test
    void converge_whenDemoPortfolioAbsent_seedsDemoUserOnly() {
        DemoPortfolioInitializer initializer = initializer(true);
        when(portfolioRepository.findByUserId(DemoPortfolioInitializer.DEMO_USER_ID))
                .thenReturn(List.of());
        when(seedService.desiredHoldings(DemoPortfolioInitializer.DEMO_USER_ID))
                .thenReturn(List.of(AAPL_DESIRED));

        DemoPortfolioInitializer.Outcome outcome = initializer.converge();

        assertThat(outcome).isEqualTo(DemoPortfolioInitializer.Outcome.SEEDED);
        verify(seedService).seed(DemoPortfolioInitializer.DEMO_USER_ID);
        verify(seedService, never()).seed(org.mockito.ArgumentMatchers.argThat(
                id -> !DemoPortfolioInitializer.DEMO_USER_ID.equals(id)));
        assertThat(logAppender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(msg -> msg.contains("event=demo_portfolio_seeded")
                        && msg.contains("replica=")
                        && msg.contains(DemoPortfolioInitializer.DEMO_USER_ID));
    }

    @Test
    void converge_whenHoldingsMatchDesiredState_isNoOp() {
        DemoPortfolioInitializer initializer = initializer(true);
        Portfolio portfolio = portfolioWithId();
        AssetHolding holding = matchingHolding(portfolio, AAPL_DESIRED);
        when(portfolioRepository.findByUserId(DemoPortfolioInitializer.DEMO_USER_ID))
                .thenReturn(List.of(portfolio));
        when(assetHoldingRepository.findByPortfolio(portfolio)).thenReturn(List.of(holding));
        when(seedService.desiredHoldings(DemoPortfolioInitializer.DEMO_USER_ID))
                .thenReturn(List.of(AAPL_DESIRED));

        DemoPortfolioInitializer.Outcome outcome = initializer.converge();

        assertThat(outcome).isEqualTo(DemoPortfolioInitializer.Outcome.CONVERGED);
        verify(seedService, never()).seed(anyString());
        assertThat(logAppender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(msg -> msg.contains("event=demo_portfolio_converged")
                        && msg.contains("replica="));
    }

    @Test
    void converge_whenQuantityDiffers_reseeds() {
        assertReseedsWhen(holding -> holding.setQuantity(new BigDecimal("99")));
    }

    @Test
    void converge_whenCostBasisDiffers_reseeds() {
        assertReseedsWhen(holding -> holding.setAvgCostBasis(new BigDecimal("1.0000")));
    }

    @Test
    void converge_whenExtraTickerPresent_reseeds() {
        DemoPortfolioInitializer initializer = initializer(true);
        Portfolio portfolio = portfolioWithId();
        AssetHolding aapl = matchingHolding(portfolio, AAPL_DESIRED);
        AssetHolding extra = new AssetHolding(portfolio, "ZZZZ", BigDecimal.ONE);
        extra.setAvgCostBasis(BigDecimal.ONE);
        extra.setCostBasisCurrency("USD");
        extra.setCostBasisSource("SEED");
        extra.setCostBasisAsOf(ANCHOR);
        when(portfolioRepository.findByUserId(DemoPortfolioInitializer.DEMO_USER_ID))
                .thenReturn(List.of(portfolio));
        when(assetHoldingRepository.findByPortfolio(portfolio)).thenReturn(List.of(aapl, extra));
        when(seedService.desiredHoldings(DemoPortfolioInitializer.DEMO_USER_ID))
                .thenReturn(List.of(AAPL_DESIRED));

        assertThat(initializer.converge()).isEqualTo(DemoPortfolioInitializer.Outcome.SEEDED);
        verify(seedService).seed(DemoPortfolioInitializer.DEMO_USER_ID);
    }

    @Test
    void converge_whenTickerMissing_reseeds() {
        DemoPortfolioInitializer initializer = initializer(true);
        Portfolio portfolio = portfolioWithId();
        when(portfolioRepository.findByUserId(DemoPortfolioInitializer.DEMO_USER_ID))
                .thenReturn(List.of(portfolio));
        when(assetHoldingRepository.findByPortfolio(portfolio)).thenReturn(List.of());
        when(seedService.desiredHoldings(DemoPortfolioInitializer.DEMO_USER_ID))
                .thenReturn(List.of(AAPL_DESIRED));

        assertThat(initializer.converge()).isEqualTo(DemoPortfolioInitializer.Outcome.SEEDED);
        verify(seedService).seed(DemoPortfolioInitializer.DEMO_USER_ID);
    }

    @Test
    void converge_acquiresAdvisoryLockBeforeCompareOrSeed() {
        DemoPortfolioInitializer initializer = initializer(true);
        when(portfolioRepository.findByUserId(DemoPortfolioInitializer.DEMO_USER_ID))
                .thenReturn(List.of());
        when(seedService.desiredHoldings(DemoPortfolioInitializer.DEMO_USER_ID))
                .thenReturn(List.of(AAPL_DESIRED));

        initializer.converge();

        InOrder order = inOrder(session, portfolioRepository, seedService);
        order.verify(session).doWork(any());
        order.verify(portfolioRepository).findByUserId(DemoPortfolioInitializer.DEMO_USER_ID);
        order.verify(seedService).seed(DemoPortfolioInitializer.DEMO_USER_ID);
    }

    private void assertReseedsWhen(java.util.function.Consumer<AssetHolding> mutator) {
        DemoPortfolioInitializer initializer = initializer(true);
        Portfolio portfolio = portfolioWithId();
        AssetHolding holding = matchingHolding(portfolio, AAPL_DESIRED);
        mutator.accept(holding);
        when(portfolioRepository.findByUserId(DemoPortfolioInitializer.DEMO_USER_ID))
                .thenReturn(List.of(portfolio));
        when(assetHoldingRepository.findByPortfolio(portfolio)).thenReturn(List.of(holding));
        when(seedService.desiredHoldings(DemoPortfolioInitializer.DEMO_USER_ID))
                .thenReturn(List.of(AAPL_DESIRED));

        assertThat(initializer.converge()).isEqualTo(DemoPortfolioInitializer.Outcome.SEEDED);
        verify(seedService).seed(DemoPortfolioInitializer.DEMO_USER_ID);
    }

    private DemoPortfolioInitializer initializer(boolean seedOnStartup) {
        return new DemoPortfolioInitializer(
                new DemoProperties(seedOnStartup, ANCHOR),
                entityManager,
                portfolioRepository,
                assetHoldingRepository,
                seedService,
                passthroughTransactionManager(),
                noopDiagnostics());
    }

    private static SpecA912StartupTransactionDiagnostics noopDiagnostics() {
        return org.mockito.Mockito.mock(SpecA912StartupTransactionDiagnostics.class);
    }

    private static PlatformTransactionManager passthroughTransactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }

    private static Portfolio portfolioWithId() {
        Portfolio portfolio = new Portfolio(DemoPortfolioInitializer.DEMO_USER_ID);
        ReflectionTestUtils.setField(portfolio, "id", UUID.randomUUID());
        return portfolio;
    }

    private static AssetHolding matchingHolding(Portfolio portfolio,
                                                PortfolioSeedService.DesiredHolding desired) {
        AssetHolding holding = new AssetHolding(portfolio, desired.ticker(), desired.quantity());
        holding.setAvgCostBasis(desired.avgCostBasis());
        holding.setCostBasisCurrency(desired.costBasisCurrency());
        holding.setCostBasisSource(desired.costBasisSource());
        holding.setCostBasisAsOf(desired.costBasisAsOf());
        return holding;
    }
}
