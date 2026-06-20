package com.wealth.market;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MarketDataRefreshJobRunnerTest {

    private GenericApplicationContext context;
    private MarketDataRefreshService refreshService;
    private MarketDataRefreshJobRunner runner;
    private AtomicInteger capturedExitCode;

    @BeforeEach
    void setUp() {
        context = new GenericApplicationContext();
        context.refresh();
        refreshService = mock(MarketDataRefreshService.class);
        runner = new MarketDataRefreshJobRunner(refreshService, context);
        capturedExitCode = new AtomicInteger(-1);
        runner.exitHandler = capturedExitCode::set;
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void exitsZeroWhenRefreshSucceeds() {
        runner.run();

        verify(refreshService).refresh();
        assertThat(capturedExitCode.get()).isZero();
    }

    @Test
    void exitsOneWhenRefreshFails() {
        doThrow(new RuntimeException("refresh failed")).when(refreshService).refresh();

        runner.run();

        verify(refreshService).refresh();
        assertThat(capturedExitCode.get()).isEqualTo(1);
    }
}
