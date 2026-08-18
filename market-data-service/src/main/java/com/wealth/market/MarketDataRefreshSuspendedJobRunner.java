package com.wealth.market;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/**
 * Suspended-mode Job runner: start, write nothing, emit {@code refresh_suspended}, flush, exit 0.
 *
 * <p>Mutually exclusive with {@link MarketDataRefreshJobRunner}. Activated only when
 * {@code market-data.job-runner.enabled} is explicitly {@code false}.
 */
@Component
@ConditionalOnProperty(prefix = "market-data.job-runner", name = "enabled", havingValue = "false")
public class MarketDataRefreshSuspendedJobRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(MarketDataRefreshSuspendedJobRunner.class);

    private static final long SPAN_FLUSH_TIMEOUT_SECONDS = 30;

    private final ConfigurableApplicationContext context;
    private final Environment environment;

    IntConsumer exitHandler = System::exit;

    public MarketDataRefreshSuspendedJobRunner(
            ConfigurableApplicationContext context, Environment environment) {
        this.context = context;
        this.environment = environment;
    }

    @Override
    public void run(String... args) {
        String executionIdentity = executionIdentity();
        log.info("refresh_suspended execution={}", executionIdentity);
        flushSpans();
        exitHandler.accept(SpringApplication.exit(context, () -> 0));
    }

    private String executionIdentity() {
        String replica = environment.getProperty("CONTAINER_APP_REPLICA_NAME");
        if (replica != null && !replica.isBlank()) {
            return replica;
        }
        String job = environment.getProperty("CONTAINER_APP_JOB_NAME");
        if (job != null && !job.isBlank()) {
            return job;
        }
        return environment.getProperty("HOSTNAME", "unknown");
    }

    private void flushSpans() {
        try {
            SdkTracerProvider tracerProvider =
                    context.getBeanProvider(SdkTracerProvider.class).getIfAvailable();
            if (tracerProvider == null) {
                return;
            }
            CompletableResultCode result =
                    tracerProvider.forceFlush().join(SPAN_FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!result.isDone() || !result.isSuccess()) {
                log.warn("MarketDataRefreshSuspendedJobRunner: span flush did not complete successfully");
            }
        } catch (Exception e) {
            log.warn("MarketDataRefreshSuspendedJobRunner: span flush failed", e);
        }
    }
}
