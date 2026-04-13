package com.wealth.insight.advisor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.RepeatedTest;

import com.wealth.insight.dto.PortfolioDto;
import com.wealth.insight.dto.PortfolioHoldingDto;
import com.wealth.insight.infrastructure.ai.MockInsightAdvisor;

/**
 * Property-based tests for {@link AnalysisResult} invariants.
 * Uses {@code @RepeatedTest(100)} for lightweight PBT with random portfolios.
 */
class AnalysisResultPropertyTest {

    private final MockInsightAdvisor advisor = new MockInsightAdvisor();

    /**
     * Property: For any randomly generated portfolio, AnalysisResult invariants hold:
     * - Empty portfolio → riskScore 0
     * - Non-empty portfolio → riskScore in [1, 100]
     * - rebalancingSuggestions size ≤ 3
     * - All lists are non-null
     */
    @RepeatedTest(100)
    void analysisResult_invariants_hold_for_any_portfolio() {
        PortfolioDto portfolio = randomPortfolio();
        AnalysisResult result = advisor.analyze(portfolio);

        assertThat(result).isNotNull();
        assertThat(result.concentrationWarnings()).isNotNull();
        assertThat(result.rebalancingSuggestions()).isNotNull();
        assertThat(result.rebalancingSuggestions()).hasSizeLessThanOrEqualTo(3);

        if (portfolio.holdings().isEmpty()) {
            assertThat(result.riskScore()).isZero();
            assertThat(result.concentrationWarnings()).isEmpty();
            assertThat(result.rebalancingSuggestions()).isEmpty();
        } else {
            assertThat(result.riskScore()).isBetween(1, 100);
        }
    }

    /**
     * Property: AnalysisResult lists are defensively copied — mutating the
     * original list passed to the constructor does not affect the record.
     */
    @RepeatedTest(100)
    void analysisResult_lists_are_defensively_copied() {
        List<String> warnings = new ArrayList<>(List.of("warning-" + ThreadLocalRandom.current().nextInt()));
        List<String> suggestions = new ArrayList<>(List.of("suggestion-" + ThreadLocalRandom.current().nextInt()));

        AnalysisResult result = new AnalysisResult(50, warnings, suggestions);

        // Mutate originals
        warnings.add("extra");
        suggestions.add("extra");

        assertThat(result.concentrationWarnings()).hasSize(1);
        assertThat(result.rebalancingSuggestions()).hasSize(1);
    }

    private static PortfolioDto randomPortfolio() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int holdingCount = rng.nextInt(0, 11); // 0–10 holdings
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
