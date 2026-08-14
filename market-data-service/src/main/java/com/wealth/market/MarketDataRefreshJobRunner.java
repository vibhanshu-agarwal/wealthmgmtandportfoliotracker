package com.wealth.market;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

@Component
@ConditionalOnProperty(prefix = "market-data.job-runner", name = "enabled", havingValue = "true")
public class MarketDataRefreshJobRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(MarketDataRefreshJobRunner.class);

    private static final long SPAN_FLUSH_TIMEOUT_SECONDS = 30;

    private final MarketDataRefreshService refreshService;
    private final ConfigurableApplicationContext context;

    IntConsumer exitHandler = System::exit;

    public MarketDataRefreshJobRunner(MarketDataRefreshService refreshService,
                                      ConfigurableApplicationContext context) {
        this.refreshService = refreshService;
        this.context = context;
    }

    @Override
    public void run(String... args) {
        int exitCode = 0;
        try {
            log.info("MarketDataRefreshJobRunner: starting one-shot refresh");
            refreshService.refresh();
            log.info("MarketDataRefreshJobRunner: refresh complete, shutting down");
        } catch (Exception e) {
            log.error("MarketDataRefreshJobRunner: refresh failed", e);
            exitCode = 1;
        } finally {
            flushSpans();
            final int finalExitCode = exitCode;
            exitHandler.accept(SpringApplication.exit(context, () -> finalExitCode));
        }
    }

    private void flushSpans() {
        try {
            SdkTracerProvider tracerProvider = context.getBeanProvider(SdkTracerProvider.class).getIfAvailable();
            if (tracerProvider == null) {
                return;
            }
            CompletableResultCode result = tracerProvider.forceFlush()
                    .join(SPAN_FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!result.isDone() || !result.isSuccess()) {
                log.warn("MarketDataRefreshJobRunner: span flush did not complete successfully");
            }
        } catch (Exception e) {
            log.warn("MarketDataRefreshJobRunner: span flush failed", e);
        }
    }
}
