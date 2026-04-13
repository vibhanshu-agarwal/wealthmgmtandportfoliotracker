package com.wealth.insight.infrastructure.ai;

import com.wealth.insight.advisor.AnalysisResult;
import com.wealth.insight.dto.PortfolioDto;
import com.wealth.insight.dto.PortfolioHoldingDto;
import org.junit.jupiter.api.RepeatedTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link MockInsightAdvisor} determinism.
 * Verifies that any non-empty portfolio produces a non-empty analysis.
 */
class MockInsightAdvisorPropertyTest {

    private final MockInsightAdvisor advisor = new MockInsightAdvisor();

    /**
     * Property: For any non-empty portfolio, MockInsightAdvisor returns:
     * - riskScore > 0
     * - At least one concentration warning
     * - At least one rebalancing suggestion
     */
    @RepeatedTest(100)
    void nonEmptyPortfolio_always_produces_nonEmptyAnalysis() {
        PortfolioDto portfolio = randomNonEmptyPortfolio();

        AnalysisResult result = advisor.analyze(portfolio);

        assertThat(result.riskScore()).isGreaterThan(0);
        assertThat(result.concentrationWarnings()).isNotEmpty();
        assertThat(result.rebalancingSuggestions()).isNotEmpty();
    }

    /**
     * Property: MockInsightAdvisor is deterministic — same input always produces same output.
     */
    @RepeatedTest(100)
    void sameInput_always_produces_sameOutput() {
        PortfolioDto portfolio = randomNonEmptyPortfolio();

        AnalysisResult first = advisor.analyze(portfolio);
        AnalysisResult second = advisor.analyze(portfolio);

        assertThat(first.riskScore()).isEqualTo(second.riskScore());
        assertThat(first.concentrationWarnings()).isEqualTo(second.concentrationWarnings());
        assertThat(first.rebalancingSuggestions()).isEqualTo(second.rebalancingSuggestions());
    }

    private static PortfolioDto randomNonEmptyPortfolio() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int holdingCount = rng.nextInt(1, 11); // 1–10 holdings
        List<PortfolioHoldingDto> holdings = new ArrayList<>();
        for (int i = 0; i < holdingCount; i++) {
            holdings.add(new PortfolioHoldingDto(
                    UUID.randomUUID(),
                    randomTicker(rng),
                    BigDecimal.valueOf(rng.nextDouble(0.01, 10000.0))
            ));
        }
        return new PortfolioDto(UUID.randomUUID(), "user-" + rng.nextInt(), Instant.now(), holdings);
    }

    private static String randomTicker(ThreadLocalRandom rng) {
        String[] tickers = {"AAPL", "GOOG", "MSFT", "AMZN", "BTC", "ETH", "TSLA", "NVDA", "META", "NFLX"};
        return tickers[rng.nextInt(tickers.length)];
    }
}
