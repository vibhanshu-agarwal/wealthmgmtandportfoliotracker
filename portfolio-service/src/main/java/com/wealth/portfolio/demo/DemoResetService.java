package com.wealth.portfolio.demo;

import com.wealth.portfolio.Portfolio;
import com.wealth.portfolio.PortfolioRepository;
import com.wealth.portfolio.PortfolioResponse;
import com.wealth.portfolio.PortfolioService;
import com.wealth.portfolio.composition.GoldenStateTuplePreparer;
import com.wealth.portfolio.composition.HoldingReplacementService;
import com.wealth.portfolio.composition.CompositionResult;
import com.wealth.portfolio.seed.DemoPortfolioInitializer;
import com.wealth.portfolio.seed.DemoProperties;
import com.wealth.portfolio.seed.SeedTickerRegistry;
import io.micrometer.tracing.Tracer;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Orchestrates identity-preserving demo portfolio reset via B1's replacement primitive.
 *
 * <p>The demo UUID is compiled in; callers supply only {@code expectedVersion}. No version re-read,
 * conflict retry, or presence coupling occurs on this path.
 */
@Service
public class DemoResetService {

    private static final Logger log = LoggerFactory.getLogger(DemoResetService.class);

    static final String DEMO_USER_ID = DemoPortfolioInitializer.DEMO_USER_ID;

    private final HoldingReplacementService replacementService;
    private final PortfolioRepository portfolioRepository;
    private final PortfolioService portfolioService;
    private final SeedTickerRegistry seedTickerRegistry;
    private final DemoProperties demoProperties;
    private final Tracer tracer;

    public DemoResetService(
            HoldingReplacementService replacementService,
            PortfolioRepository portfolioRepository,
            PortfolioService portfolioService,
            SeedTickerRegistry seedTickerRegistry,
            DemoProperties demoProperties,
            Tracer tracer) {
        this.replacementService = replacementService;
        this.portfolioRepository = portfolioRepository;
        this.portfolioService = portfolioService;
        this.seedTickerRegistry = seedTickerRegistry;
        this.demoProperties = demoProperties;
        this.tracer = tracer;
    }

    @Transactional
    public PortfolioResponse reset(long expectedVersion) {
        GoldenStateTuplePreparer preparer =
                new GoldenStateTuplePreparer(
                        seedTickerRegistry, DEMO_USER_ID, demoProperties.costBasisAnchor());

        CompositionResult result =
                replacementService.replace(DEMO_USER_ID, expectedVersion, List.of(), preparer);

        Portfolio portfolio =
                portfolioRepository
                        .findById(result.portfolioId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Portfolio missing after reset: "
                                                        + result.portfolioId()));
        portfolio.getHoldings().size();

        PortfolioResponse response = portfolioService.toPortfolioResponse(portfolio);
        if (response.version() != result.version()
                || !response.id().equals(result.portfolioId())) {
            throw new IllegalStateException(
                    "Response projection disagrees with replacement result");
        }

        registerSuccessLogAfterCommit(result.version());
        return response;
    }

    private void registerSuccessLogAfterCommit(long version) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        String traceId = activeTraceId();
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        String previousTraceId = MDC.get("traceId");
                        if (traceId != null) {
                            MDC.put("traceId", traceId);
                        }
                        try {
                            log.info("event=demo_reset_succeeded version={}", version);
                        } finally {
                            if (traceId != null) {
                                if (previousTraceId != null) {
                                    MDC.put("traceId", previousTraceId);
                                } else {
                                    MDC.remove("traceId");
                                }
                            }
                        }
                    }
                });
    }

    private String activeTraceId() {
        var context = tracer.currentTraceContext().context();
        if (context != null) {
            return context.traceId();
        }
        return MDC.get("traceId");
    }
}
