package com.wealth.portfolio.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wealth.portfolio.Portfolio;
import com.wealth.portfolio.PortfolioRepository;
import com.wealth.portfolio.PortfolioResponse;
import com.wealth.portfolio.PortfolioService;
import com.wealth.portfolio.composition.CompositionResult;
import com.wealth.portfolio.composition.DesiredHoldingState;
import com.wealth.portfolio.composition.GoldenStateTuplePreparer;
import com.wealth.portfolio.composition.HoldingReplacementService;
import com.wealth.portfolio.composition.PortfolioVersionConflictException;
import com.wealth.portfolio.composition.TuplePreparer;
import com.wealth.portfolio.seed.DemoProperties;
import com.wealth.portfolio.seed.SeedTickerRegistry;
import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.Tracer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class DemoResetServiceTest {

    private static final Instant ANCHOR = Instant.parse("2020-01-01T00:00:00Z");

    @Mock private HoldingReplacementService replacementService;
    @Mock private PortfolioRepository portfolioRepository;
    @Mock private PortfolioService portfolioService;
    @Mock private SeedTickerRegistry seedTickerRegistry;
    @Mock private Tracer tracer;
    @Mock private CurrentTraceContext currentTraceContext;

    private DemoResetService service;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        lenient().when(tracer.currentTraceContext()).thenReturn(currentTraceContext);
        lenient().when(currentTraceContext.context()).thenReturn(null);
        service =
                new DemoResetService(
                        replacementService,
                        portfolioRepository,
                        portfolioService,
                        seedTickerRegistry,
                        new DemoProperties(false, ANCHOR),
                        tracer);
        Logger logger = (Logger) LoggerFactory.getLogger(DemoResetService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        Logger logger = (Logger) LoggerFactory.getLogger(DemoResetService.class);
        logger.detachAppender(logAppender);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void reset_callsReplaceOnceWithFixedDemoUserEmptyIntentAndGoldenPreparer() {
        UUID portfolioId = UUID.randomUUID();
        CompositionResult compositionResult = compositionResult(portfolioId, 4L);
        Portfolio portfolio = portfolio(portfolioId, 4L);
        PortfolioResponse mapped = response(portfolioId, 4L);

        when(replacementService.replace(
                        eq(DemoResetService.DEMO_USER_ID),
                        eq(3L),
                        eq(List.of()),
                        any(GoldenStateTuplePreparer.class)))
                .thenReturn(compositionResult);
        when(portfolioRepository.findById(portfolioId)).thenReturn(Optional.of(portfolio));
        when(portfolioService.toPortfolioResponse(portfolio)).thenReturn(mapped);

        PortfolioResponse result = service.reset(3L);

        assertThat(result).isSameAs(mapped);

        ArgumentCaptor<TuplePreparer> preparerCaptor = ArgumentCaptor.forClass(TuplePreparer.class);
        verify(replacementService)
                .replace(
                        eq(DemoResetService.DEMO_USER_ID),
                        eq(3L),
                        eq(List.of()),
                        preparerCaptor.capture());
        assertThat(preparerCaptor.getValue()).isInstanceOf(GoldenStateTuplePreparer.class);

        InOrder order = inOrder(replacementService, portfolioRepository, portfolioService);
        order.verify(replacementService)
                .replace(
                        eq(DemoResetService.DEMO_USER_ID),
                        eq(3L),
                        eq(List.of()),
                        any(GoldenStateTuplePreparer.class));
        order.verify(portfolioRepository).findById(portfolioId);
        order.verify(portfolioService).toPortfolioResponse(portfolio);
    }

    @Test
    void reset_doesNotRetryOnVersionConflict() {
        when(replacementService.replace(
                        eq(DemoResetService.DEMO_USER_ID),
                        anyLong(),
                        anyList(),
                        any(GoldenStateTuplePreparer.class)))
                .thenThrow(new PortfolioVersionConflictException(9L));

        assertThatThrownBy(() -> service.reset(3L))
                .isInstanceOf(PortfolioVersionConflictException.class);

        verify(replacementService)
                .replace(
                        eq(DemoResetService.DEMO_USER_ID),
                        eq(3L),
                        eq(List.of()),
                        any(GoldenStateTuplePreparer.class));
        verify(portfolioRepository, never()).findById(any());
        verify(portfolioService, never()).toPortfolioResponse(any());
        assertThat(logAppender.list).isEmpty();
    }

    @Test
    void reset_doesNotLogWhenSynchronizationInactive() {
        UUID portfolioId = UUID.randomUUID();
        CompositionResult compositionResult = compositionResult(portfolioId, 5L);
        Portfolio portfolio = portfolio(portfolioId, 5L);
        PortfolioResponse mapped = response(portfolioId, 5L);

        when(replacementService.replace(
                        eq(DemoResetService.DEMO_USER_ID),
                        eq(2L),
                        eq(List.of()),
                        any(GoldenStateTuplePreparer.class)))
                .thenReturn(compositionResult);
        when(portfolioRepository.findById(portfolioId)).thenReturn(Optional.of(portfolio));
        when(portfolioService.toPortfolioResponse(portfolio)).thenReturn(mapped);

        service.reset(2L);

        assertThat(logAppender.list).isEmpty();
    }

    @Test
    void reset_logsSuccessOnlyAfterCommitWhenSynchronizationActive() {
        UUID portfolioId = UUID.randomUUID();
        CompositionResult compositionResult = compositionResult(portfolioId, 5L);
        Portfolio portfolio = portfolio(portfolioId, 5L);
        PortfolioResponse mapped = response(portfolioId, 5L);

        when(replacementService.replace(
                        eq(DemoResetService.DEMO_USER_ID),
                        eq(2L),
                        eq(List.of()),
                        any(GoldenStateTuplePreparer.class)))
                .thenReturn(compositionResult);
        when(portfolioRepository.findById(portfolioId)).thenReturn(Optional.of(portfolio));
        when(portfolioService.toPortfolioResponse(portfolio)).thenReturn(mapped);

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.reset(2L);
            assertThat(logAppender.list).isEmpty();
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
        } finally {
            TransactionSynchronizationManager.clear();
        }

        assertThat(logAppender.list)
                .singleElement()
                .extracting(ILoggingEvent::getFormattedMessage)
                .isEqualTo("event=demo_reset_succeeded version=5");
    }

    private static CompositionResult compositionResult(UUID portfolioId, long version) {
        return new CompositionResult(
                portfolioId,
                DemoResetService.DEMO_USER_ID,
                Instant.parse("2020-01-01T00:00:00Z"),
                Instant.parse("2020-01-02T00:00:00Z"),
                version,
                List.of(
                        new DesiredHoldingState(
                                "AAPL",
                                BigDecimal.ONE,
                                BigDecimal.ONE,
                                "USD",
                                "SEED",
                                ANCHOR)),
                false,
                false);
    }

    private static Portfolio portfolio(UUID portfolioId, long version) {
        Portfolio portfolio = new Portfolio(DemoResetService.DEMO_USER_ID);
        setField(portfolio, "id", portfolioId);
        setField(portfolio, "version", version);
        return portfolio;
    }

    private static PortfolioResponse response(UUID portfolioId, long version) {
        return new PortfolioResponse(
                portfolioId,
                DemoResetService.DEMO_USER_ID,
                Instant.parse("2020-01-01T00:00:00Z"),
                Instant.parse("2020-01-02T00:00:00Z"),
                version,
                List.of());
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }
}
