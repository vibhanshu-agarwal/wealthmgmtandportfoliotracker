package com.wealth.insight.infrastructure.ai;

import com.wealth.insight.advisor.AnalysisResult;
import com.wealth.insight.advisor.InsightAdvisor;
import com.wealth.insight.dto.PortfolioDto;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Default mock adapter — active when neither {@code ollama} nor {@code bedrock}
 * profiles are enabled. Returns deterministic, hardcoded responses with zero latency.
 */
@Service
@Profile("!ollama & !bedrock")
public class MockInsightAdvisor implements InsightAdvisor {

    @Override
    public AnalysisResult analyze(PortfolioDto portfolio) {
        if (portfolio.holdings().isEmpty()) {
            return new AnalysisResult(0, List.of(), List.of());
        }

        return new AnalysisResult(
                42,
                List.of("Portfolio is concentrated in technology sector (>40% allocation)"),
                List.of(
                        "Consider diversifying into bonds or fixed-income assets to reduce volatility",
                        "Reduce single-stock exposure to below 20% of total portfolio value"
                )
        );
    }
}
