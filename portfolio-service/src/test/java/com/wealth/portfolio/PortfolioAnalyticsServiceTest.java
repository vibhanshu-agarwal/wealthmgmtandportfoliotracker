package com.wealth.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.wealth.portfolio.dto.PortfolioAnalyticsDto;
import com.wealth.portfolio.dto.PortfolioAnalyticsDto.HoldingAnalyticsDto;
import com.wealth.portfolio.dto.PortfolioAnalyticsDto.PerformanceCoverageDto;
import com.wealth.portfolio.dto.PortfolioAnalyticsDto.PerformancePointDto;
import com.wealth.portfolio.fx.FxProperties;
import com.wealth.portfolio.seed.SeedTickerRegistry;
import com.wealth.user.UserRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Unit tests for {@link PortfolioAnalyticsService}.
 *
 * <p>Covers core logic, Task 5 correctness properties, and parameterised edge cases:
 * <ul>
 *   <li>Task 5.1: real P&amp;L from cost basis (nullable; never coerced to 0)</li>
 *   <li>Task 5.2: tolerance-window 24h change (nullable; change basis label)</li>
 *   <li>Task 5.3: performance series coverage metadata (partial / synthetic flags)</li>
 *   <li>Task 5.4: canonical display asset-class mapping</li>
 * </ul>
 */
class PortfolioAnalyticsServiceTest {

    private JdbcTemplate jdbcTemplate;
    private UserRepository userRepository;
    private PortfolioRepository portfolioRepository;
    private FxRateProvider fxRateProvider;
    private FxProperties fxProperties;
    private SeedTickerRegistry seedTickerRegistry;
    private PortfolioAnalyticsService service;

    private static final String USER_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String BASE_CURRENCY = "USD";

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        userRepository = mock(UserRepository.class);
        fxRateProvider = mock(FxRateProvider.class);
        fxProperties = mock(FxProperties.class);
        seedTickerRegistry = mock(SeedTickerRegistry.class);
        PortfolioRepository pr = mock(PortfolioRepository.class);
        this.portfolioRepository = pr;

        when(fxProperties.baseCurrency()).thenReturn(BASE_CURRENCY);
        when(userRepository.existsById(UUID.fromString(USER_ID))).thenReturn(true);
        when(portfolioRepository.existsByUserId(USER_ID)).thenReturn(true);
        // Default: any ticker not explicitly stubbed → empty → OTHER
        when(seedTickerRegistry.find(anyString())).thenReturn(Optional.empty());

        service = new PortfolioAnalyticsService(
                jdbcTemplate, userRepository, portfolioRepository,
                fxRateProvider, fxProperties, seedTickerRegistry);
    }

    // ── Core behaviour tests ─────────────────────────────────────────────────

    @Test
    void singleHolding_sameCurrency_noFxCall() {
        stubQuery(
                List.of(
                        holdingRow("AAPL", "10", "200.00", "USD", null, null, null, null, null),
                        historyRow("AAPL", "USD", daysAgo(2), "195.00"),
                        historyRow("AAPL", "USD", daysAgo(1), "198.00"),
                        historyRow("AAPL", "USD", daysAgo(0), "200.00"),
                        historyRow("AAPL", "USD", daysAgo(3), "192.00"),
                        historyRow("AAPL", "USD", daysAgo(4), "191.00"),
                        historyRow("AAPL", "USD", daysAgo(5), "190.00"),
                        historyRow("AAPL", "USD", daysAgo(6), "189.00")));

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        verifyNoInteractions(fxRateProvider);
        assertThat(result.totalValue()).isEqualByComparingTo("2000.0000");
        assertThat(result.holdings()).hasSize(1);
        assertThat(result.holdings().getFirst().currentValueBase()).isEqualByComparingTo("2000.0000");
    }

    @Test
    void singleHolding_foreignCurrency_fxApplied() {
        when(fxRateProvider.getRate("EUR", "USD")).thenReturn(new BigDecimal("1.10"));

        stubQuery(
                List.of(
                        holdingRow("AAPL", "10", "100.00", "EUR", null, null, null, null, null),
                        historyRow("AAPL", "EUR", daysAgo(1), "98.00"),
                        historyRow("AAPL", "EUR", daysAgo(2), "97.00"),
                        historyRow("AAPL", "EUR", daysAgo(3), "96.00"),
                        historyRow("AAPL", "EUR", daysAgo(4), "95.00"),
                        historyRow("AAPL", "EUR", daysAgo(5), "94.00"),
                        historyRow("AAPL", "EUR", daysAgo(6), "93.00"),
                        historyRow("AAPL", "EUR", daysAgo(7), "92.00")));

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        // 10 × 100 × 1.10 = 1100
        assertThat(result.totalValue()).isEqualByComparingTo("1100.0000");
        assertThat(result.holdings().getFirst().currentValueBase()).isEqualByComparingTo("1100.0000");
    }

    @Test
    void emptyHoldings_returnsSentinelAndZeroTotals() {
        stubQuery(List.of());

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        assertThat(result.totalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.totalCostBasis()).isEqualByComparingTo(BigDecimal.ZERO);
        // Task 5.1: null P&L for empty portfolio (no basis available)
        assertThat(result.totalUnrealizedPnL()).isNull();
        assertThat(result.totalUnrealizedPnLPercent()).isNull();
        assertThat(result.holdings()).isEmpty();
        assertThat(result.performanceSeries()).isEmpty();
        assertThat(result.bestPerformer().ticker()).isEqualTo("N/A");
        assertThat(result.worstPerformer().ticker()).isEqualTo("N/A");
    }

    @Test
    void userNotFound_throwsUserNotFoundException() {
        when(portfolioRepository.existsByUserId(USER_ID)).thenReturn(false);
        when(userRepository.existsById(UUID.fromString(USER_ID))).thenReturn(false);

        assertThatThrownBy(() -> service.getAnalytics(USER_ID))
                .isInstanceOf(UserNotFoundException.class);
    }

    // ── Task 5.1: Unrealised P&L tests ──────────────────────────────────────

    // ── Issue #1 & #2: Missing current price (no market_prices row) ─────────

    @Test
    void missingPrice_currentPriceNull_currentValueBaseNullNotZero() {
        // No market_prices row → currentPrice is null in the SQL result.
        // currentValueBase must be null (not 0) to avoid a false $0.00 position value.
        stubQuery(List.of(holdingRow("AAPL", "10", null, "USD", null, null, null, null, null)));

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        HoldingAnalyticsDto h = result.holdings().getFirst();
        assertThat(h.currentPrice()).isNull();
        assertThat(h.currentValueBase()).isNull();
    }

    @Test
    void missingPrice_pnlIsNull_neverFalse100PctLoss() {
        // A holding with basis but no price must not produce a false −100% loss.
        // P&L requires a current value; without a price it must be null.
        stubQuery(List.of(holdingRow("AAPL", "10", null, "USD", null, null, null, "180.00", "USD")));

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        HoldingAnalyticsDto h = result.holdings().getFirst();
        assertThat(h.unrealizedPnL()).isNull();
        assertThat(h.unrealizedPnLPercent()).isNull();
    }

    @Test
    void missingPrice_changeIsNull_neverFalse100PctChange() {
        // A holding with history but no current price must not produce a false −100% change.
        Instant refAt = Instant.now().minus(24, ChronoUnit.HOURS);
        stubQuery(List.of(holdingRow("AAPL", "10", null, "USD", "190.00", refAt, "WITHIN_24H_WINDOW", null, null)));

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        HoldingAnalyticsDto h = result.holdings().getFirst();
        assertThat(h.change24hPercent()).isNull();
        assertThat(h.change24hAbsolute()).isNull();
    }

    @Test
    void missingPrice_excludedFromTotalValue() {
        // Holdings with null price must not contribute $0 to the total.
        // AAPL has a price, BTC does not → totalValue = AAPL only.
        stubQuery(List.of(
                holdingRow("AAPL", "10", "200.00", "USD", null, null, null, null, null),
                holdingRow("BTC-USD", "0.5", null, "USD", null, null, null, null, null)));

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        assertThat(result.totalValue()).isEqualByComparingTo("2000.0000");
    }

    @Test
    void task51_realPnL_noBasis_returnsNullPnL_neverZero() {        // No avg_cost_basis provided → unrealizedPnL must be null, not 0
        stubQuery(List.of(holdingRow("AAPL", "10", "200.00", "USD", null, null, null, null, null)));

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        HoldingAnalyticsDto h = result.holdings().getFirst();
        assertThat(h.unrealizedPnL()).isNull();
        assertThat(h.unrealizedPnLPercent()).isNull();
        // Top-level aggregate also null
        assertThat(result.totalUnrealizedPnL()).isNull();
        assertThat(result.totalUnrealizedPnLPercent()).isNull();
    }

    @Test
    void task51_realPnL_withBasis_sameCurrency() {
        // AAPL cost basis = 180 USD, current price = 200 USD
        // Qty = 10, P&L = (200 - 180) × 10 = +200
        stubQuery(List.of(holdingRow("AAPL", "10", "200.00", "USD", null, null, null, "180.00", "USD")));

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        HoldingAnalyticsDto h = result.holdings().getFirst();
        assertThat(h.unrealizedPnL()).isNotNull();
        assertThat(h.unrealizedPnL()).isEqualByComparingTo("200.0000");
        assertThat(h.unrealizedPnLPercent()).isNotNull();
        // (200 - 180) / 180 × 100 ≈ 11.1111%
        assertThat(h.unrealizedPnLPercent()).isEqualByComparingTo("11.1111");

        // Aggregate P&L is non-null when at least one holding has basis
        assertThat(result.totalUnrealizedPnL()).isNotNull();
        assertThat(result.totalUnrealizedPnL()).isEqualByComparingTo("200.0000");
    }

    @Test
    void task51_realPnL_negativePnL_basisAboveCurrentPrice() {
        // Cost basis = 220 USD > current price 200 USD → negative P&L
        stubQuery(List.of(holdingRow("AAPL", "5", "200.00", "USD", null, null, null, "220.00", "USD")));

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        HoldingAnalyticsDto h = result.holdings().getFirst();
        assertThat(h.unrealizedPnL()).isNotNull();
        assertThat(h.unrealizedPnL()).isEqualByComparingTo("-100.0000");
        assertThat(h.unrealizedPnLPercent()).isNotNull();
        assertThat(h.unrealizedPnLPercent()).isNegative();
    }

    @Test
    void task51_realPnL_crossCurrencyBasis_fxApplied() {
        // INFY.NS: quote currency INR, cost basis also INR = 1400
        // Current price = 1500 INR, qty = 5
        // FX rate INR→USD = 0.012
        // currentValueBase = 5 × 1500 × 0.012 = 90
        // costBasisBase    = 5 × 1400 × 0.012 = 84
        // P&L = 90 - 84 = +6
        when(fxRateProvider.getRate("INR", "USD")).thenReturn(new BigDecimal("0.012"));

        stubQuery(List.of(holdingRow("INFY.NS", "5", "1500.00", "INR", null, null, null, "1400.00", "INR")));

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        HoldingAnalyticsDto h = result.holdings().getFirst();
        assertThat(h.unrealizedPnL()).isNotNull();
        assertThat(h.unrealizedPnL()).isEqualByComparingTo("6.0000");
    }

    @Test
    void task51_realPnL_genuinelyCrossCurrency_differentQuoteAndBasis() {
        // A US stock bought when priced in EUR (e.g. dual-listed or historical basis in EUR).
        // quote = USD (current price), cost basis = EUR.
        // current price = 200 USD, qty = 10
        // FX USD→USD = 1.0 (same currency)
        // FX EUR→USD = 1.10
        // currentValueBase = 10 × 200 × 1.0 = 2000
        // costBasisBase    = 10 × 165.00 × 1.10 = 1815
        // P&L = 2000 - 1815 = +185
        when(fxRateProvider.getRate("EUR", "USD")).thenReturn(new BigDecimal("1.10"));

        stubQuery(List.of(holdingRow("AAPL", "10", "200.00", "USD", null, null, null, "165.00", "EUR")));

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        HoldingAnalyticsDto h = result.holdings().getFirst();
        assertThat(h.unrealizedPnL()).isNotNull();
        assertThat(h.unrealizedPnL()).isEqualByComparingTo("185.0000");
        assertThat(h.costBasisCurrency()).isEqualTo("EUR");
    }

    @Test
    void task51_aggregate_excludesHoldingWhenCostBasisFxUnavailable() {
        // AAPL (USD quote, USD basis) — included; P&L = (200-180)×10 = +200
        // INFY.NS (INR quote, INR basis) — quote FX available, but cost-basis FX unavailable
        // → the INFY.NS holding must be excluded from BOTH totalValue and totalCostBasis
        //   to avoid reporting its full value as profit.
        when(fxRateProvider.getRate("INR", "USD"))
                .thenThrow(new FxRateUnavailableException("INR", "USD", null));

        stubQuery(List.of(
                holdingRow("AAPL",    "10", "200.00", "USD", null, null, null, "180.00", "USD"),
                holdingRow("INFY.NS", "5",  "1500.00", "INR", null, null, null, "1400.00", "INR")));
        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        // AAPL P&L: (200-180)×10 = 200
        assertThat(result.totalUnrealizedPnL()).isNotNull();
        assertThat(result.totalUnrealizedPnL()).isEqualByComparingTo("200.0000");
        // INFY excluded → totalValue only from AAPL = 2000
        assertThat(result.totalValue()).isEqualByComparingTo("2000.0000");
        assertThat(result.partialValuation()).isTrue();
    }

    @Test
    void task51_pnlIdentity_totalPnLEqualsTotalValueMinusTotalCostBasis() {
        // When basis is present, totalPnL = totalValue - totalCostBasis
        stubQuery(List.of(
                holdingRow("AAPL", "10", "200.00", "USD", null, null, null, "180.00", "USD"),
                historyRow("AAPL", "USD", daysAgo(1), "195.00"),
                historyRow("AAPL", "USD", daysAgo(2), "193.00"),
                historyRow("AAPL", "USD", daysAgo(3), "191.00"),
                historyRow("AAPL", "USD", daysAgo(4), "189.00"),
                historyRow("AAPL", "USD", daysAgo(5), "187.00"),
                historyRow("AAPL", "USD", daysAgo(6), "185.00"),
                historyRow("AAPL", "USD", daysAgo(7), "183.00")));

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        assertThat(result.totalUnrealizedPnL()).isNotNull();
        assertThat(result.totalUnrealizedPnL())
                .isEqualByComparingTo(result.totalValue().subtract(result.totalCostBasis()));
    }

    @Test
    void task51_mixedHoldings_oneWithBasisOneWithout_aggregateNonNull() {
        // One holding has basis, one does not; aggregate should still be non-null (from the holding that has it)
        stubQuery(List.of(
                holdingRow("AAPL", "10", "200.00", "USD", null, null, null, "180.00", "USD"),
                holdingRow("TSLA", "5", "300.00", "USD", null, null, null, null, null)));

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        // AAPL has basis, TSLA does not
        HoldingAnalyticsDto aapl = result.holdings().stream()
                .filter(h -> "AAPL".equals(h.ticker())).findFirst().orElseThrow();
        HoldingAnalyticsDto tsla = result.holdings().stream()
                .filter(h -> "TSLA".equals(h.ticker())).findFirst().orElseThrow();
        assertThat(aapl.unrealizedPnL()).isNotNull();
        assertThat(tsla.unrealizedPnL()).isNull();

        // Aggregate non-null because at least one holding has basis
        assertThat(result.totalUnrealizedPnL()).isNotNull();
    }

    // ── Task 5.2: Tolerance-window change tests ──────────────────────────────

    @Test
    void task52_noReferenceInWindow_changeIsNull() {
        // price24hAgo = null means no row fell in the 18–36h window
        stubQuery(List.of(holdingRow("AAPL", "5", "150.00", "USD", null, null, null, null, null)));

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        HoldingAnalyticsDto h = result.holdings().getFirst();
        assertThat(h.change24hPercent()).isNull();
        assertThat(h.change24hAbsolute()).isNull();
        assertThat(h.changeBasis()).isNull();
        assertThat(h.change24hReferenceAt()).isNull();
    }

    @Test
    void task52_referenceInWindow_changeNonNullAndLabeledCorrectly() {
        // Reference at 24h ago is in the 18–36h window → label WITHIN_24H_WINDOW
        Instant refAt = Instant.now().minus(24, ChronoUnit.HOURS);
        stubQuery(List.of(holdingRow("AAPL", "10", "200.00", "USD", "190.00", refAt, "WITHIN_24H_WINDOW", null, null)));

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        HoldingAnalyticsDto h = result.holdings().getFirst();
        assertThat(h.change24hPercent()).isNotNull();
        assertThat(h.change24hAbsolute()).isNotNull();
        assertThat(h.changeBasis()).isEqualTo("WITHIN_24H_WINDOW");
        assertThat(h.change24hReferenceAt()).isNotNull();
    }

    @Test
    void task52_referenceSinceSnapshot_labeledCorrectly() {
        // Reference is older than 36h (snapshot fallback) → SINCE_PREVIOUS_SNAPSHOT
        Instant refAt = Instant.now().minus(48, ChronoUnit.HOURS);
        stubQuery(List.of(holdingRow("AAPL", "10", "200.00", "USD", "185.00", refAt, "SINCE_PREVIOUS_SNAPSHOT", null, null)));

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        HoldingAnalyticsDto h = result.holdings().getFirst();
        assertThat(h.changeBasis()).isEqualTo("SINCE_PREVIOUS_SNAPSHOT");
        assertThat(h.change24hPercent()).isNotNull();
        assertThat(h.change24hReferenceAt()).isNotNull();
    }

    @Test
    void task52_changeFormula_correctValues() {
        Instant refAt = Instant.now().minus(24, ChronoUnit.HOURS);
        // current = 200, ref = 100 → +100%
        stubQuery(List.of(holdingRow("AAPL", "1", "200.00", "USD", "100.00", refAt, "WITHIN_24H_WINDOW", null, null)));

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        HoldingAnalyticsDto h = result.holdings().getFirst();
        assertThat(h.change24hPercent()).isEqualByComparingTo("100.0000");
        assertThat(h.change24hAbsolute()).isEqualByComparingTo("100.0000");
    }

    @Test
    void task52_changeBasisLabel_withinWindow() {
        // 24h ago is within 18–36h window
        assertThat(service.computeChangeBasis(Instant.now().minus(24, ChronoUnit.HOURS)))
                .isEqualTo("WITHIN_24H_WINDOW");
        assertThat(service.computeChangeBasis(Instant.now().minus(18, ChronoUnit.HOURS)))
                .isEqualTo("WITHIN_24H_WINDOW");
        assertThat(service.computeChangeBasis(Instant.now().minus(36, ChronoUnit.HOURS)))
                .isEqualTo("WITHIN_24H_WINDOW");
    }

    @Test
    void task52_changeBasisLabel_nullWhenNoReference() {
        assertThat(service.computeChangeBasis(null)).isNull();
    }

    @Test
    void task52_change24hPercent_nullWhenNoReference() {
        assertThat(service.computeChange24hPercent(new BigDecimal("200"), null)).isNull();
    }

    @Test
    void task52_change24hPercent_zeroWhenRefIsZero() {
        assertThat(service.computeChange24hPercent(new BigDecimal("200"), BigDecimal.ZERO))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── Task 5.2: parameterised change formula ───────────────────────────────

    @ParameterizedTest
    @MethodSource("change24hCases")
    void task52_change24hFormula(String current, String ago, String expectedPct) {
        BigDecimal result = service.computeChange24hPercent(
                new BigDecimal(current), ago == null ? null : new BigDecimal(ago));
        if (expectedPct == null) {
            assertThat(result).isNull();
        } else {
            assertThat(result).isEqualByComparingTo(new BigDecimal(expectedPct));
        }
    }

    static Stream<Arguments> change24hCases() {
        return Stream.of(
                // Equal prices → 0%
                Arguments.of("200", "200", "0"),
                // Doubled → 100%
                Arguments.of("200", "100", "100.0000"),
                // Halved → −50%
                Arguments.of("50", "100", "-50.0000"),
                // price24hAgo = 0 → 0 (no division)
                Arguments.of("200", "0", "0"),
                // price24hAgo = null → null (Task 5.2: not 0)
                Arguments.of("200", null, null)
        );
    }

    // ── Task 5.3: Performance series coverage tests ──────────────────────────

    @Test
    void task53_syntheticSeries_coveredMarkedPartialAndSynthetic() {
        // Fewer than 7 history dates → synthetic, partial=true, synthetic=true
        stubQuery(List.of(
                holdingRow("AAPL", "10", "200.00", "USD", null, null, null, null, null),
                historyRow("AAPL", "USD", daysAgo(1), "195.00"),
                historyRow("AAPL", "USD", daysAgo(2), "193.00")
        ));

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        assertThat(result.performanceSeries()).hasSize(7);
        assertThat(result.performanceSeries().getLast().value())
                .isEqualByComparingTo(result.totalValue());

        PerformanceCoverageDto cov = result.performanceCoverage();
        assertThat(cov.partial()).isTrue();
        assertThat(cov.synthetic()).isTrue();
        assertThat(cov.totalHoldings()).isEqualTo(1);
    }

    @Test
    void task53_realSeries_allHoldingsCovered_notPartial() {
        // 7 distinct dates AND all holdings have history → partial=false, synthetic=false
        List<AnalyticsQueryRow> rows = new ArrayList<>();
        rows.add(holdingRow("AAPL", "10", "200", "USD", null, null, null, null, null));
        for (int i = 6; i >= 0; i--) {
            rows.add(historyRow("AAPL", "USD", daysAgo(i), "19" + i + ".00"));
        }
        stubQuery(rows);

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        PerformanceCoverageDto cov = result.performanceCoverage();
        assertThat(cov.partial()).isFalse();
        assertThat(cov.synthetic()).isFalse();
        assertThat(cov.holdingsWithHistory()).isEqualTo(1);
        assertThat(cov.totalHoldings()).isEqualTo(1);
    }

    @Test
    void task53_realSeries_someHoldingsMissingHistory_markedPartial() {
        // AAPL has history, TSLA does not → partial=true, synthetic=false (>= 7 dates from AAPL)
        List<AnalyticsQueryRow> rows = new ArrayList<>();
        rows.add(holdingRow("AAPL", "10", "200", "USD", null, null, null, null, null));
        rows.add(holdingRow("TSLA", "5", "300", "USD", null, null, null, null, null));
        for (int i = 6; i >= 0; i--) {
            rows.add(historyRow("AAPL", "USD", daysAgo(i), "19" + i + ".00"));
        }
        stubQuery(rows);

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        PerformanceCoverageDto cov = result.performanceCoverage();
        assertThat(cov.partial()).isTrue();
        assertThat(cov.synthetic()).isFalse();
        assertThat(cov.holdingsWithHistory()).isEqualTo(1);
        assertThat(cov.totalHoldings()).isEqualTo(2);
    }

    // ── Task 5.4: Asset class mapping tests ─────────────────────────────────

    @Test
    void task54_displayAssetClass_usEquity_mapsToStock() {
        stubSeedTicker("AAPL", "US_EQUITY", "USD");
        stubQuery(List.of(holdingRow("AAPL", "1", "200", "USD", null, null, null, null, null)));

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        assertThat(result.holdings().getFirst().displayAssetClass()).isEqualTo("STOCK");
    }

    @Test
    void task54_displayAssetClass_nse_mapsToStock() {
        stubSeedTicker("RELIANCE.NS", "NSE", "INR");
        when(fxRateProvider.getRate("INR", "USD")).thenReturn(new BigDecimal("0.012"));
        stubQuery(List.of(holdingRow("RELIANCE.NS", "1", "2800", "INR", null, null, null, null, null)));

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        assertThat(result.holdings().getFirst().displayAssetClass()).isEqualTo("STOCK");
    }

    @Test
    void task54_displayAssetClass_crypto_mapsToCrypto() {
        stubSeedTicker("BTC-USD", "CRYPTO", "USD");
        stubQuery(List.of(holdingRow("BTC-USD", "0.5", "67000", "USD", null, null, null, null, null)));

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        assertThat(result.holdings().getFirst().displayAssetClass()).isEqualTo("CRYPTO");
    }

    @Test
    void task54_displayAssetClass_forex_mapsToCash() {
        stubSeedTicker("EURUSD=X", "FOREX", "USD");
        stubQuery(List.of(holdingRow("EURUSD=X", "1000", "1.08", "USD", null, null, null, null, null)));

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        assertThat(result.holdings().getFirst().displayAssetClass()).isEqualTo("CASH");
    }

    @Test
    void task54_displayAssetClass_unknownTicker_mapsToOther() {
        // Unknown ticker (registry returns empty) → OTHER
        when(seedTickerRegistry.find("UNKNOWN")).thenReturn(Optional.empty());
        stubQuery(List.of(holdingRow("UNKNOWN", "1", "50", "USD", null, null, null, null, null)));

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        assertThat(result.holdings().getFirst().displayAssetClass()).isEqualTo("OTHER");
    }

    @ParameterizedTest
    @MethodSource("assetClassMappingCases")
    void task54_displayAssetClassMapper_allValues(String rawClass, String expectedDisplay) {
        assertThat(DisplayAssetClassMapper.map(rawClass)).isEqualTo(expectedDisplay);
    }

    static Stream<Arguments> assetClassMappingCases() {
        return Stream.of(
                Arguments.of("US_EQUITY", "STOCK"),
                Arguments.of("NSE",       "STOCK"),
                Arguments.of("CRYPTO",    "CRYPTO"),
                Arguments.of("FOREX",     "CASH"),
                Arguments.of("BOND",      "BOND"),
                Arguments.of("COMMODITY", "COMMODITY"),
                Arguments.of("UNKNOWN",   "OTHER"),
                Arguments.of(null,        "OTHER")
        );
    }

    // ── Property 1: Performer ordering invariant ─────────────────────────────

    @ParameterizedTest
    @MethodSource("holdingListsForPerformerOrdering")
    void property1_bestPerformerChangeGteWorstPerformerChange(List<AnalyticsQueryRow> rows) {
        stubQuery(rows);

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        // Only compare performers when both have non-null change
        if (result.bestPerformer().change24hPercent() != null
                && result.worstPerformer().change24hPercent() != null) {
            assertThat(result.bestPerformer().change24hPercent())
                    .isGreaterThanOrEqualTo(result.worstPerformer().change24hPercent());
        }
    }

    static Stream<Arguments> holdingListsForPerformerOrdering() {
        Instant ref24h = Instant.now().minus(24, ChronoUnit.HOURS);
        return Stream.of(
                // Single holding with change
                Arguments.of(List.of(holdingRow("AAPL", "10", "200", "USD", "190", ref24h, "WITHIN_24H_WINDOW", null, null))),
                // Two holdings with equal change
                Arguments.of(List.of(
                        holdingRow("AAPL", "10", "200", "USD", "190", ref24h, "WITHIN_24H_WINDOW", null, null),
                        holdingRow("TSLA", "5", "300", "USD", "285", ref24h, "WITHIN_24H_WINDOW", null, null))),
                // Two holdings with different change
                Arguments.of(List.of(
                        holdingRow("AAPL", "10", "200", "USD", "180", ref24h, "WITHIN_24H_WINDOW", null, null),
                        holdingRow("TSLA", "5", "300", "USD", "310", ref24h, "WITHIN_24H_WINDOW", null, null))),
                // Mixed change / no-reference
                Arguments.of(List.of(
                        holdingRow("AAPL", "10", "200", "USD", "180", ref24h, "WITHIN_24H_WINDOW", null, null),
                        holdingRow("TSLA", "5", "300", "USD", null, null, null, null, null)))
        );
    }

    // ── Property 2: Value decomposition ─────────────────────────────────────

    @ParameterizedTest
    @MethodSource("holdingValueDecompositionCases")
    void property2_totalValueEqualsSumOfHoldingValues(List<AnalyticsQueryRow> rows) {
        stubQuery(rows);

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        BigDecimal sumOfHoldings = result.holdings().stream()
                .map(HoldingAnalyticsDto::currentValueBase)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(result.totalValue()).isEqualByComparingTo(sumOfHoldings);
    }

    static Stream<Arguments> holdingValueDecompositionCases() {
        return Stream.of(
                Arguments.of(List.of(holdingRow("AAPL", "10", "200", "USD", null, null, null, null, null))),
                Arguments.of(List.of(
                        holdingRow("AAPL", "10", "200", "USD", null, null, null, null, null),
                        holdingRow("TSLA", "5", "300", "USD", null, null, null, null, null))),
                Arguments.of(List.of(
                        holdingRow("AAPL", "10", "200", "USD", null, null, null, null, null),
                        holdingRow("TSLA", "5", "300", "USD", null, null, null, null, null),
                        holdingRow("BTC", "0.5", "70000", "USD", null, null, null, null, null)))
        );
    }

    // ── Property 3: Performance series ordering ──────────────────────────────

    @ParameterizedTest
    @MethodSource("seriesOrderingCases")
    void property3_performanceSeriesIsAscendingByDate(List<AnalyticsQueryRow> rows) {
        stubQuery(rows);

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        List<PerformancePointDto> series = result.performanceSeries();
        for (int i = 1; i < series.size(); i++) {
            assertThat(series.get(i).date()).isGreaterThan(series.get(i - 1).date());
        }
    }

    static Stream<Arguments> seriesOrderingCases() {
        // 7 history rows → real series path
        List<AnalyticsQueryRow> sevenDays = new ArrayList<>();
        sevenDays.add(holdingRow("AAPL", "10", "200", "USD", null, null, null, null, null));
        for (int i = 6; i >= 0; i--) {
            sevenDays.add(historyRow("AAPL", "USD", daysAgo(i), "19" + i + ".00"));
        }

        // 2 history rows → synthetic path
        List<AnalyticsQueryRow> twoDays = List.of(
                holdingRow("AAPL", "10", "200", "USD", null, null, null, null, null),
                historyRow("AAPL", "USD", daysAgo(1), "195.00"),
                historyRow("AAPL", "USD", daysAgo(2), "193.00"));

        return Stream.of(Arguments.of(sevenDays), Arguments.of(twoDays));
    }

    // ── Property 4: Series change consistency ────────────────────────────────

    @ParameterizedTest
    @MethodSource("seriesChangeCases")
    void property4_seriesChangeConsistency(List<AnalyticsQueryRow> rows) {
        stubQuery(rows);

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);
        List<PerformancePointDto> series = result.performanceSeries();

        assertThat(series.getFirst().change()).isEqualByComparingTo(BigDecimal.ZERO);
        for (int i = 1; i < series.size(); i++) {
            BigDecimal expectedChange = series.get(i).value().subtract(series.get(i - 1).value());
            assertThat(series.get(i).change()).isEqualByComparingTo(expectedChange);
        }
    }

    static Stream<Arguments> seriesChangeCases() {
        List<AnalyticsQueryRow> rows = new ArrayList<>();
        rows.add(holdingRow("AAPL", "10", "200", "USD", null, null, null, null, null));
        for (int i = 6; i >= 0; i--) {
            rows.add(historyRow("AAPL", "USD", daysAgo(i), String.valueOf(190 + i)));
        }
        return Stream.of(Arguments.of(rows));
    }

    // ── Property 5: Synthetic series anchor and length ───────────────────────

    @ParameterizedTest
    @MethodSource("syntheticSeriesCases")
    void property5_syntheticSeriesAnchorAndLength(String anchorStr, int days) {
        BigDecimal anchor = new BigDecimal(anchorStr);
        List<PerformancePointDto> series = service.generateSyntheticSeries(anchor, days);

        assertThat(series).hasSize(days);
        assertThat(series.getLast().value()).isEqualByComparingTo(anchor);
        assertThat(series.getFirst().change()).isEqualByComparingTo(BigDecimal.ZERO);

        for (int i = 1; i < series.size(); i++) {
            assertThat(series.get(i).date()).isGreaterThan(series.get(i - 1).date());
        }
        for (int i = 1; i < series.size(); i++) {
            BigDecimal expectedChange = series.get(i).value().subtract(series.get(i - 1).value());
            assertThat(series.get(i).change()).isEqualByComparingTo(expectedChange);
        }
    }

    static Stream<Arguments> syntheticSeriesCases() {
        return Stream.of(
                Arguments.of("10000.00", 1),
                Arguments.of("10000.00", 7),
                Arguments.of("50000.00", 30),
                Arguments.of("0.00", 7)
        );
    }

    // ── Test helpers ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void stubQuery(List<AnalyticsQueryRow> rows) {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(rows);
    }

    /**
     * Stubs the seed registry to return a ticker with the given asset class.
     */
    private void stubSeedTicker(String ticker, String assetClass, String quoteCurrency) {
        SeedTickerRegistry.SeedTicker st = new SeedTickerRegistry.SeedTicker(
                ticker, assetClass, quoteCurrency, BigDecimal.ONE, ticker, List.of());
        when(seedTickerRegistry.find(ticker)).thenReturn(Optional.of(st));
    }

    /**
     * Constructs a HOLDING row with cost-basis and reference fields.
     *
     * @param price24h      reference price (in-window or snapshot); null = no reference
     * @param price24hRefAt observed_at of the reference; null when no reference
     * @param refLabel      "WITHIN_24H_WINDOW" | "SINCE_PREVIOUS_SNAPSHOT" | null
     * @param avgCostBasis  null = basis unavailable
     * @param costBasisCcy  null when avgCostBasis is null
     */
    private static AnalyticsQueryRow holdingRow(
            String ticker, String qty, String price, String currency,
            String price24h, Instant price24hRefAt, String refLabel,
            String avgCostBasis, String costBasisCcy) {
        return new AnalyticsQueryRow(
                "HOLDING",
                ticker,
                new BigDecimal(qty),
                price != null ? new BigDecimal(price) : null,
                currency,
                price24h != null ? new BigDecimal(price24h) : null,
                price24hRefAt,
                refLabel,
                avgCostBasis != null ? new BigDecimal(avgCostBasis) : null,
                costBasisCcy,
                null,
                null);
    }

    private static AnalyticsQueryRow historyRow(
            String ticker, String currency, String date, String price) {
        return new AnalyticsQueryRow(
                "HISTORY", ticker, null, null, currency, null, null, null, null, null,
                date, new BigDecimal(price));
    }

    private static String daysAgo(int days) {
        return LocalDate.now().minusDays(days).toString();
    }
}
