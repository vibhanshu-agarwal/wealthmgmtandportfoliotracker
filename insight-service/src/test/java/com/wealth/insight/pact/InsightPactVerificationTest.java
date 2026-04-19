package com.wealth.insight.pact;

import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import au.com.dius.pact.provider.spring.spring7.Spring7MockMvcTestTarget;
import com.wealth.insight.AiInsightService;
import com.wealth.insight.GlobalExceptionHandler;
import com.wealth.insight.InsightController;
import com.wealth.insight.InsightService;
import com.wealth.insight.MarketDataService;
import com.wealth.insight.dto.TickerSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;

/**
 * Pact provider verification test for insight-service.
 *
 * <p>Replays consumer contracts from {@code frontend/pacts/} against the
 * {@link InsightController} using a standalone MockMvc setup — no full
 * application context required.
 */
@ExtendWith(MockitoExtension.class)
@Provider("insight-service")
@PactFolder("../frontend/pacts")
class InsightPactVerificationTest {

    @Mock
    private InsightService insightService;

    @Mock
    private MarketDataService marketDataService;

    @Mock
    private AiInsightService aiInsightService;

    @InjectMocks
    private InsightController insightController;

    @BeforeEach
    void setUp(PactVerificationContext context) {
        Spring7MockMvcTestTarget testTarget = new Spring7MockMvcTestTarget();
        testTarget.setControllers(insightController);
        testTarget.setControllerAdvices(new GlobalExceptionHandler());
        context.setTarget(testTarget);
    }

    @TestTemplate
    @ExtendWith(au.com.dius.pact.provider.spring.spring7.PactVerificationSpring7Provider.class)
    void pactVerificationTestTemplate(PactVerificationContext context) {
        context.verifyInteraction();
    }

    @State("market summary data is available")
    void marketSummaryDataIsAvailable() {
        TickerSummary aaplSummary = new TickerSummary(
                "AAPL",
                new BigDecimal("178.50"),
                List.of(new BigDecimal("175.00")),
                new BigDecimal("2.00"),
                null
        );

        Map<String, TickerSummary> summaries = new LinkedHashMap<>();
        summaries.put("AAPL", aaplSummary);

        // The list endpoint no longer calls aiInsightService — no stub needed.
        when(marketDataService.getMarketSummary()).thenReturn(summaries);
    }
}
