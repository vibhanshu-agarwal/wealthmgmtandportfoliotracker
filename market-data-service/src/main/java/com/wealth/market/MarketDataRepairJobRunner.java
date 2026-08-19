package com.wealth.market;

import com.wealth.market.repair.MongoMmNsRepairService;
import com.wealth.market.repair.RepairResult;
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
@ConditionalOnProperty(prefix = "market-data.repair", name = "enabled", havingValue = "true")
public class MarketDataRepairJobRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(MarketDataRepairJobRunner.class);
    private static final long SPAN_FLUSH_TIMEOUT_SECONDS = 30;

    private final MongoMmNsRepairService repairService;
    private final ConfigurableApplicationContext context;

    IntConsumer exitHandler = System::exit;

    public MarketDataRepairJobRunner(
            MongoMmNsRepairService repairService, ConfigurableApplicationContext context) {
        this.repairService = repairService;
        this.context = context;
    }

    @Override
    public void run(String... args) {
        int exitCode = 1;
        try {
            log.info("MarketDataRepairJobRunner: starting MM.NS Mongo repair");
            RepairResult result = repairService.run();
            exitCode = result.exitCode();
            log.info(
                    "MarketDataRepairJobRunner: finished outcome={} generation={} exit={}",
                    result.outcome(),
                    result.generation(),
                    exitCode);
        } catch (Exception e) {
            log.error("MarketDataRepairJobRunner: repair failed", e);
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
                log.warn("MarketDataRepairJobRunner: span flush did not complete successfully");
            }
        } catch (Exception e) {
            log.warn("MarketDataRepairJobRunner: span flush failed", e);
        }
    }
}
