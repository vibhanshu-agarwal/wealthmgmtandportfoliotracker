package com.wealth.market;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void forceFlushAfterRefreshWhenProviderPresent_exitsZeroOnSuccess() {
        SdkTracerProvider tracerProvider = registerSuccessfulTracerProvider();
        AtomicBoolean exitAlreadySetAtFlush = recordWhetherExitAlreadySetOnFlush(tracerProvider);

        runner.run();

        var order = inOrder(refreshService, tracerProvider);
        order.verify(refreshService).refresh();
        order.verify(tracerProvider).forceFlush();
        assertThat(exitAlreadySetAtFlush).isFalse();
        assertThat(capturedExitCode.get()).isZero();
    }

    @Test
    void forceFlushAfterRefreshWhenProviderPresent_exitsOneOnRefreshFailure() {
        SdkTracerProvider tracerProvider = registerSuccessfulTracerProvider();
        doThrow(new RuntimeException("refresh failed")).when(refreshService).refresh();
        AtomicBoolean exitAlreadySetAtFlush = recordWhetherExitAlreadySetOnFlush(tracerProvider);

        runner.run();

        var order = inOrder(refreshService, tracerProvider);
        order.verify(refreshService).refresh();
        order.verify(tracerProvider).forceFlush();
        assertThat(exitAlreadySetAtFlush).isFalse();
        assertThat(capturedExitCode.get()).isEqualTo(1);
    }

    @Test
    void forceFlushThrowDoesNotChangeExitCodeOnSuccess() {
        SdkTracerProvider tracerProvider = mock(SdkTracerProvider.class);
        when(tracerProvider.forceFlush()).thenThrow(new RuntimeException("flush failed"));
        registerTracerProvider(tracerProvider);

        assertThatCode(() -> runner.run()).doesNotThrowAnyException();

        verify(tracerProvider).forceFlush();
        assertThat(capturedExitCode.get()).isZero();
    }

    @Test
    void forceFlushThrowDoesNotChangeExitCodeOnRefreshFailure() {
        doThrow(new RuntimeException("refresh failed")).when(refreshService).refresh();
        SdkTracerProvider tracerProvider = mock(SdkTracerProvider.class);
        when(tracerProvider.forceFlush()).thenThrow(new RuntimeException("flush failed"));
        registerTracerProvider(tracerProvider);

        assertThatCode(() -> runner.run()).doesNotThrowAnyException();

        verify(tracerProvider).forceFlush();
        assertThat(capturedExitCode.get()).isEqualTo(1);
    }

    @Test
    void forceFlushTimeoutDoesNotChangeExitCodeOnSuccess() {
        TimingOutFlush flush = registerTimingOutTracerProvider();

        assertThatCode(() -> runner.run()).doesNotThrowAnyException();

        verify(flush.tracerProvider()).forceFlush();
        verify(flush.result()).join(anyLong(), any(TimeUnit.class));
        assertThat(capturedExitCode.get()).isZero();
    }

    @Test
    void forceFlushTimeoutDoesNotChangeExitCodeOnRefreshFailure() {
        doThrow(new RuntimeException("refresh failed")).when(refreshService).refresh();
        TimingOutFlush flush = registerTimingOutTracerProvider();

        assertThatCode(() -> runner.run()).doesNotThrowAnyException();

        verify(flush.tracerProvider()).forceFlush();
        verify(flush.result()).join(anyLong(), any(TimeUnit.class));
        assertThat(capturedExitCode.get()).isEqualTo(1);
    }

    private SdkTracerProvider registerSuccessfulTracerProvider() {
        SdkTracerProvider tracerProvider = mock(SdkTracerProvider.class);
        when(tracerProvider.forceFlush()).thenReturn(CompletableResultCode.ofSuccess());
        registerTracerProvider(tracerProvider);
        return tracerProvider;
    }

    private TimingOutFlush registerTimingOutTracerProvider() {
        SdkTracerProvider tracerProvider = mock(SdkTracerProvider.class);
        CompletableResultCode pending = mock(CompletableResultCode.class);
        when(pending.join(anyLong(), any(TimeUnit.class))).thenReturn(pending);
        when(pending.isDone()).thenReturn(false);
        when(pending.isSuccess()).thenReturn(false);
        when(tracerProvider.forceFlush()).thenReturn(pending);
        registerTracerProvider(tracerProvider);
        return new TimingOutFlush(tracerProvider, pending);
    }

    private record TimingOutFlush(SdkTracerProvider tracerProvider, CompletableResultCode result) {}

    private void registerTracerProvider(SdkTracerProvider tracerProvider) {
        context.getBeanFactory().registerSingleton("sdkTracerProvider", tracerProvider);
    }

    private AtomicBoolean recordWhetherExitAlreadySetOnFlush(SdkTracerProvider tracerProvider) {
        AtomicBoolean exitAlreadySetAtFlush = new AtomicBoolean(false);
        when(tracerProvider.forceFlush()).thenAnswer(invocation -> {
            exitAlreadySetAtFlush.set(capturedExitCode.get() != -1);
            return CompletableResultCode.ofSuccess();
        });
        return exitAlreadySetAtFlush;
    }
}
