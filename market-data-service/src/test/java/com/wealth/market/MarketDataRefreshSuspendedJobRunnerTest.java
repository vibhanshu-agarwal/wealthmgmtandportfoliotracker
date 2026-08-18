package com.wealth.market;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketDataRefreshSuspendedJobRunnerTest {

    private GenericApplicationContext context;
    private MarketDataRefreshSuspendedJobRunner runner;
    private AtomicInteger capturedExitCode;
    private MockEnvironment environment;

    @BeforeEach
    void setUp() {
        context = new GenericApplicationContext();
        context.refresh();
        environment = new MockEnvironment();
        environment.setProperty("CONTAINER_APP_REPLICA_NAME", "market-data-refresh-job-abc123");
        runner = new MarketDataRefreshSuspendedJobRunner(context, environment);
        capturedExitCode = new AtomicInteger(-1);
        runner.exitHandler = capturedExitCode::set;
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void exitsZeroAndFlushesBeforeExit() {
        SdkTracerProvider tracerProvider = mock(SdkTracerProvider.class);
        AtomicBoolean exitAlreadySetAtFlush = new AtomicBoolean(false);
        when(tracerProvider.forceFlush())
                .thenAnswer(
                        invocation -> {
                            exitAlreadySetAtFlush.set(capturedExitCode.get() != -1);
                            return CompletableResultCode.ofSuccess();
                        });
        context.getBeanFactory().registerSingleton("sdkTracerProvider", tracerProvider);

        runner.run();

        var order = inOrder(tracerProvider);
        order.verify(tracerProvider).forceFlush();
        assertThat(exitAlreadySetAtFlush).isFalse();
        assertThat(capturedExitCode.get()).isZero();
    }

    @Test
    void exitsZeroWhenNoTracerProvider() {
        runner.run();
        assertThat(capturedExitCode.get()).isZero();
    }
}
