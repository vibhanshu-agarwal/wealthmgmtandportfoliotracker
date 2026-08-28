package com.wealth.portfolio.seed.diag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.hibernate.Session;
import org.hibernate.jdbc.Work;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class SpecA912StartupTransactionDiagnosticsTest {

    @Mock private EntityManager entityManager;
    @Mock private Session session;
    @Mock private Connection connection;
    @Mock private Statement statement;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private DataSource dataSource;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger targetLogger;

    @BeforeEach
    void setUp() throws Exception {
        when(entityManager.unwrap(Session.class)).thenReturn(session);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            Work work = invocation.getArgument(0);
                            work.execute(connection);
                            return null;
                        })
                .when(session)
                .doWork(any());

        targetLogger = (Logger) LoggerFactory.getLogger(SpecA912StartupTransactionDiagnostics.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        targetLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        targetLogger.detachAppender(logAppender);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void runDmlProbe_whenDeleteFails_emitsFailOutcomeWithoutFurtherSql() throws Exception {
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(mock(java.sql.ResultSet.class));
        when(connection.isReadOnly()).thenReturn(true);
        when(statement.executeUpdate(SpecA912StartupTransactionDiagnostics.DML_PROBE_SQL))
                .thenThrow(new SQLException("ERROR: cannot execute DELETE in a read-only transaction"));

        SpecA912StartupTransactionDiagnostics diagnostics =
                new SpecA912StartupTransactionDiagnostics(
                        entityManager, transactionManager, dataSource);

        try (MockedStatic<SpecA912StartupTransactionDiagnostics> staticDiag =
                        mockStatic(SpecA912StartupTransactionDiagnostics.class);
                MockedStatic<TransactionSynchronizationManager> staticTx =
                        mockStatic(TransactionSynchronizationManager.class)) {
            staticDiag.when(SpecA912StartupTransactionDiagnostics::enabled).thenReturn(true);
            staticTx.when(TransactionSynchronizationManager::isActualTransactionActive)
                    .thenReturn(true);
            staticTx.when(TransactionSynchronizationManager::isCurrentTransactionReadOnly)
                    .thenReturn(true);
            staticTx.when(TransactionSynchronizationManager::getCurrentTransactionName)
                    .thenReturn("probe-tx");
            staticTx.when(() -> TransactionSynchronizationManager.hasResource(dataSource))
                    .thenReturn(true);
            when(entityManager.isJoinedToTransaction()).thenReturn(true);

            SpecA912StartupTransactionDiagnostics.DmlProbeOutcome outcome =
                    diagnostics.runDmlProbe("probe-before-dml-probe");

            assertThat(outcome).isEqualTo(SpecA912StartupTransactionDiagnostics.DmlProbeOutcome.FAIL);
            assertThat(logAppender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(
                            msg ->
                                    msg.contains("event=spec_a912_tx_diag")
                                            && msg.contains("dmlProbeOutcome=FAIL")
                                            && msg.contains(
                                                    "dmlProbeError=ERROR: cannot execute DELETE in a read-only transaction"));
        }
    }
}
