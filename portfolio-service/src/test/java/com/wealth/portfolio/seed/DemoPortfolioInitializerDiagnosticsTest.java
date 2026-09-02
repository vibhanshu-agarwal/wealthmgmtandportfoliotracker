package com.wealth.portfolio.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wealth.portfolio.AssetHoldingRepository;
import com.wealth.portfolio.Portfolio;
import com.wealth.portfolio.PortfolioRepository;
import com.wealth.portfolio.seed.diag.SpecA912StartupTransactionDiagnostics;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.Session;
import org.hibernate.jdbc.Work;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DemoPortfolioInitializerDiagnosticsTest {

    private static final Instant ANCHOR = Instant.parse("2019-06-15T12:30:00Z");

    @Mock private EntityManager entityManager;
    @Mock private Session session;
    @Mock private Connection connection;
    @Mock private PreparedStatement preparedStatement;
    @Mock private PortfolioRepository portfolioRepository;
    @Mock private AssetHoldingRepository assetHoldingRepository;
    @Mock private PortfolioSeedService seedService;
    @Mock private SpecA912StartupTransactionDiagnostics txDiagnostics;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger targetLogger;

    @BeforeEach
    void setUp() throws Exception {
        when(entityManager.unwrap(Session.class)).thenReturn(session);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            Work work = invocation.getArgument(0);
                            when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
                            work.execute(connection);
                            return null;
                        })
                .when(session)
                .doWork(any());

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
    void run_whenBothFlagsTrue_failClosedBeforeDatabaseWork() {
        try (MockedStatic<SpecA912StartupTransactionDiagnostics> staticDiag =
                mockStatic(SpecA912StartupTransactionDiagnostics.class)) {
            staticDiag
                    .when(() -> SpecA912StartupTransactionDiagnostics.rejectBothFlags(true))
                    .thenReturn(true);

            DemoPortfolioInitializer initializer = initializer(true);
            initializer.run(new DefaultApplicationArguments());

            verify(txDiagnostics, never()).captureSpring(anyString(), any());
            verify(entityManager, never()).unwrap(any());
            verify(seedService, never()).seed(anyString(), anyLong());
        }
    }

    @Test
    void run_whenDiagnosticsOnly_executesRollbackProbeWithoutSeed() {
        Portfolio portfolio = portfolioWithId();
        when(portfolioRepository.findByUserId(DemoPortfolioInitializer.DEMO_USER_ID))
                .thenReturn(List.of(portfolio));
        when(assetHoldingRepository.findByPortfolio(portfolio)).thenReturn(List.of());
        when(seedService.desiredHoldings(DemoPortfolioInitializer.DEMO_USER_ID))
                .thenReturn(List.of());
        when(txDiagnostics.runDmlProbe("probe-before-dml-probe"))
                .thenReturn(SpecA912StartupTransactionDiagnostics.DmlProbeOutcome.PASS);

        AtomicReference<TransactionStatus> capturedStatus = new AtomicReference<>();

        try (MockedStatic<SpecA912StartupTransactionDiagnostics> staticDiag =
                mockStatic(SpecA912StartupTransactionDiagnostics.class)) {
            staticDiag
                    .when(() -> SpecA912StartupTransactionDiagnostics.rejectBothFlags(false))
                    .thenReturn(false);
            staticDiag
                    .when(() -> SpecA912StartupTransactionDiagnostics.diagnosticsOnly(false))
                    .thenReturn(true);

            DemoPortfolioInitializer initializer =
                    initializer(false, passthroughTransactionManager(capturedStatus));
            initializer.run(new DefaultApplicationArguments());

            verify(txDiagnostics).captureSpring(eq("run-entry"), any());
            verify(txDiagnostics).captureSpring(eq("probe-before-transaction-template"), any());
            verify(txDiagnostics).captureSpring(eq("probe-inside-transaction-template"), any());
            verify(txDiagnostics).runDmlProbe("probe-before-dml-probe");
            verify(seedService, never()).seed(anyString(), anyLong());
            verify(seedService).desiredHoldings(DemoPortfolioInitializer.DEMO_USER_ID);

            TransactionStatus status = capturedStatus.get();
            assertThat(status).isNotNull();
            assertThat(status.isRollbackOnly()).isTrue();

            assertThat(logAppender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(
                            msg ->
                                    msg.contains("event=spec_a912_tx_probe_complete")
                                            && msg.contains("dmlProbeOutcome=PASS")
                                            && msg.contains("transactionRollbackOnly=true"));
        }
    }

    @Test
    void runRollbackProbe_whenProbeThrows_logsAndDoesNotPropagate() {
        when(portfolioRepository.findByUserId(DemoPortfolioInitializer.DEMO_USER_ID))
                .thenThrow(new RuntimeException("probe-boom"));

        DemoPortfolioInitializer initializer = initializer(false);

        assertThatCode(initializer::runRollbackProbe).doesNotThrowAnyException();

        assertThat(logAppender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(msg -> msg.contains("event=spec_a912_tx_probe_failed"));
        verify(seedService, never()).seed(anyString(), anyLong());
    }

    private DemoPortfolioInitializer initializer(boolean seedOnStartup) {
        return initializer(seedOnStartup, passthroughTransactionManager(new AtomicReference<>()));
    }

    private DemoPortfolioInitializer initializer(
            boolean seedOnStartup, PlatformTransactionManager transactionManager) {
        return new DemoPortfolioInitializer(
                new DemoProperties(seedOnStartup, ANCHOR),
                entityManager,
                portfolioRepository,
                assetHoldingRepository,
                seedService,
                transactionManager,
                txDiagnostics);
    }

    private static PlatformTransactionManager passthroughTransactionManager(
            AtomicReference<TransactionStatus> capturedStatus) {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                SimpleTransactionStatus status = new SimpleTransactionStatus();
                capturedStatus.set(status);
                return status;
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
}
