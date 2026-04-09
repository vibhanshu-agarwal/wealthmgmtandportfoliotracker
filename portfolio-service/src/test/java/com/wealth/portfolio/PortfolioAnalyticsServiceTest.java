package com.wealth.portfolio;

import com.wealth.portfolio.dto.PortfolioAnalyticsDto;
import com.wealth.portfolio.dto.PortfolioAnalyticsDto.HoldingAnalyticsDto;
import com.wealth.portfolio.dto.PortfolioAnalyticsDto.PerformancePointDto;
import com.wealth.portfolio.fx.FxProperties;
import com.wealth.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PortfolioAnalyticsService}.
 *
 * <p>Covers core logic, edge cases, and the six correctness properties
 * using JUnit 5 {@code @ParameterizedTest @MethodSource}.
 */
class PortfolioAnalyticsServiceTest {

    private JdbcTemplate jdbcTemplate;
    private UserRepository userRepository;
    private FxRateProvider fxRateProvider;
    private FxProperties fxProperties;
    private PortfolioAnalyticsService service;

    private static final String USER_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String BASE_CURRENCY = "USD";

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        userRepository = mock(UserRepository.class);
        fxRateProvider = mock(FxRateProvider.class);
        fxProperties = mock(FxProperties.class);

        when(fxProperties.baseCurrency()).thenReturn(BASE_CURRENCY);
        when(userRepository.existsById(UUID.fromString(USER_ID))).thenReturn(true);

        service = new PortfolioAnalyticsService(jdbcTemplate, userRepository, fxRateProvider, fxProperties);
    }

    // ── Core behaviour tests ─────────────────────────────────────────────────

    @Test
    void singleHolding_sameCurrency_noFxCall() {
        stubQuery(List.of(
                holdingRow("AAPL", "10", "200.00", "USD", "190.00"),
                historyRow("AAPL", "USD", daysAgo(2), "195.00"),
                historyRow("AAPL", "USD", daysAgo(1), "198.00"),
                historyRow("AAPL", "USD", daysAgo(0), "200.00"),
                historyRow("AAPL", "USD", daysAgo(3), "192.00"),
                historyRow("AAPL", "USD", daysAgo(4), "191.00"),
                historyRow("AAPL", "USD", daysAgo(5), "190.00"),
                historyRow("AAPL", "USD", daysAgo(6), "189.00")
        ));

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        verifyNoInteractions(fxRateProvider);
        assertThat(result.totalValue()).isEqualByComparingTo("2000.0000");
        assertThat(result.holdings()).hasSize(1);
        assertThat(result.holdings().get(0).currentValueBase()).isEqualByComparingTo("2000.0000");
    }

    @Test
    void singleHolding_foreignCurrency_fxApplied() {
        when(fxRateProvider.getRate("EUR", "USD")).thenReturn(new BigDecimal("1.10"));

        stubQuery(List.of(
                holdingRow("AAPL", "10", "100.00", "EUR", null),
                historyRow("AAPL", "EUR", daysAgo(1), "98.00"),
                historyRow("AAPL", "EUR", daysAgo(2), "97.00"),
                historyRow("AAPL", "EUR", daysAgo(3), "96.00"),
                historyRow("AAPL", "EUR", daysAgo(4), "95.00"),
                historyRow("AAPL", "EUR", daysAgo(5), "94.00"),
                historyRow("AAPL", "EUR", daysAgo(6), "93.00"),
                historyRow("AAPL", "EUR", daysAgo(7), "92.00")
        ));

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        // 10 × 100 × 1.10 = 1100
        assertThat(result.totalValue()).isEqualByComparingTo("1100.0000");
        assertThat(result.holdings().get(0).currentValueBase()).isEqualByComparingTo("1100.0000");
    }

    @Test
    void emptyHoldings_returnsSentinelAndZeroTotals() {
        stubQuery(List.of());

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        assertThat(result.totalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.totalCostBasis()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.totalUnrealizedPnL()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.holdings()).isEmpty();
        assertThat(result.performanceSeries()).isEmpty();
        assertThat(result.bestPerformer().ticker()).isEqualTo("N/A");
        assertThat(result.worstPerformer().ticker()).isEqualTo("N/A");
    }

    @Test
    void nullPrice24hAgo_change24hIsZero_noNpe() {
        stubQuery(List.of(
                holdingRow("AAPL", "5", "150.00", "USD", null)
        ));

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        assertThat(result.holdings().get(0).change24hPercent()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.holdings().get(0).change24hAbsolute()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void fewerThan7HistoryDates_syntheticSeriesReturned() {
        stubQuery(List.of(
                holdingRow("AAPL", "10", "200.00", "USD", "190.00"),
                historyRow("AAPL", "USD", daysAgo(1), "195.00"),
                historyRow("AAPL", "USD", daysAgo(2), "193.00")
                // only 2 distinct dates — below threshold of 7
        ));

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        assertThat(result.performanceSeries()).hasSize(7);
        assertThat(result.performanceSeries().get(result.performanceSeries().size() - 1).value())
                .isEqualByComparingTo(result.totalValue());
    }

    @Test
    void pnlIdentity_totalUnrealizedPnLEqualsTotalValueMinusTotalCostBasis() {
        stubQuery(List.of(
                holdingRow("AAPL", "10", "200.00", "USD", "190.00"),
                historyRow("AAPL", "USD", daysAgo(1), "195.00"),
                historyRow("AAPL", "USD", daysAgo(2), "193.00"),
                historyRow("AAPL", "USD", daysAgo(3), "191.00"),
                historyRow("AAPL", "USD", daysAgo(4), "189.00"),
                historyRow("AAPL", "USD", daysAgo(5), "187.00"),
                historyRow("AAPL", "USD", daysAgo(6), "185.00"),
                historyRow("AAPL", "USD", daysAgo(7), "183.00")
        ));

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        assertThat(result.totalUnrealizedPnL())
                .isEqualByComparingTo(result.totalValue().subtract(result.totalCostBasis()));
    }

    @Test
    void userNotFound_throwsUserNotFoundException() {
        when(userRepository.existsById(UUID.fromString(USER_ID))).thenReturn(false);

        assertThatThrownBy(() -> service.getAnalytics(USER_ID))
                .isInstanceOf(UserNotFoundException.class);
    }

    // ── Property 1: Performer ordering invariant ─────────────────────────────

    @ParameterizedTest
    @MethodSource("holdingListsForPerformerOrdering")
    void property1_bestPerformerChangeGteWorstPerformerChange(List<AnalyticsQueryRow> rows) {
        stubQuery(rows);

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        assertThat(result.bestPerformer().change24hPercent())
                .isGreaterThanOrEqualTo(result.worstPerformer().change24hPercent());
    }

    static Stream<Arguments> holdingListsForPerformerOrdering() {
        return Stream.of(
                // Single holding
                Arguments.of(List.of(holdingRow("AAPL", "10", "200", "USD", "190"))),
                // Two holdings with equal change
                Arguments.of(List.of(
                        holdingRow("AAPL", "10", "200", "USD", "190"),
                        holdingRow("TSLA", "5", "300", "USD", "285")
                )),
                // Two holdings with different change
                Arguments.of(List.of(
                        holdingRow("AAPL", "10", "200", "USD", "180"),  // +11.1%
                        holdingRow("TSLA", "5", "300", "USD", "310")    // -3.2%
                )),
                // Many holdings in mixed order
                Arguments.of(List.of(
                        holdingRow("AAPL", "10", "200", "USD", "180"),
                        holdingRow("TSLA", "5", "300", "USD", "310"),
                        holdingRow("BTC", "1", "70000", "USD", "65000"),
                        holdingRow("ETH", "2", "3500", "USD", "3600")
                ))
        );
    }

    // ── Property 2: P&L identity ─────────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("pnlScenarios")
    void property2_pnlIdentity(String quantity, String price, String expectedPnL) {
        stubQuery(List.of(holdingRow("AAPL", quantity, price, "USD", price)));

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        BigDecimal expected = result.totalValue().subtract(result.totalCostBasis());
        assertThat(result.totalUnrealizedPnL()).isEqualByComparingTo(expected);
    }

    static Stream<Arguments> pnlScenarios() {
        return Stream.of(
                Arguments.of("10", "200.00", "0"),
                Arguments.of("1", "0.00", "0"),
                Arguments.of("100", "50.00", "0"),
                Arguments.of("0.5", "1000.00", "0")
        );
    }

    // ── Property 3: Value decomposition ─────────────────────────────────────

    @ParameterizedTest
    @MethodSource("holdingValueDecompositionCases")
    void property3_totalValueEqualsSumOfHoldingValues(List<AnalyticsQueryRow> rows) {
        stubQuery(rows);

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        BigDecimal sumOfHoldings = result.holdings().stream()
                .map(HoldingAnalyticsDto::currentValueBase)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(result.totalValue()).isEqualByComparingTo(sumOfHoldings);
    }

    static Stream<Arguments> holdingValueDecompositionCases() {
        return Stream.of(
                Arguments.of(List.of(holdingRow("AAPL", "10", "200", "USD", "190"))),
                Arguments.of(List.of(
                        holdingRow("AAPL", "10", "200", "USD", "190"),
                        holdingRow("TSLA", "5", "300", "USD", "285")
                )),
                Arguments.of(List.of(
                        holdingRow("AAPL", "10", "200", "USD", "190"),
                        holdingRow("TSLA", "5", "300", "USD", "285"),
                        holdingRow("BTC", "0.5", "70000", "USD", "68000")
                ))
        );
    }

    // ── Property 4: Performance series ordering ──────────────────────────────

    @ParameterizedTest
    @MethodSource("seriesOrderingCases")
    void property4_performanceSeriesIsAscendingByDate(List<AnalyticsQueryRow> rows) {
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
        sevenDays.add(holdingRow("AAPL", "10", "200", "USD", "190"));
        for (int i = 6; i >= 0; i--) {
            sevenDays.add(historyRow("AAPL", "USD", daysAgo(i), "19" + i + ".00"));
        }

        // 2 history rows → synthetic path (also ordered)
        List<AnalyticsQueryRow> twoDays = List.of(
                holdingRow("AAPL", "10", "200", "USD", "190"),
                historyRow("AAPL", "USD", daysAgo(1), "195.00"),
                historyRow("AAPL", "USD", daysAgo(2), "193.00")
        );

        return Stream.of(Arguments.of(sevenDays), Arguments.of(twoDays));
    }

    // ── Property 5: Series change consistency ────────────────────────────────

    @ParameterizedTest
    @MethodSource("seriesChangeCases")
    void property5_seriesChangeConsistency(List<AnalyticsQueryRow> rows) {
        stubQuery(rows);

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);
        List<PerformancePointDto> series = result.performanceSeries();

        assertThat(series.get(0).change()).isEqualByComparingTo(BigDecimal.ZERO);
        for (int i = 1; i < series.size(); i++) {
            BigDecimal expectedChange = series.get(i).value().subtract(series.get(i - 1).value());
            assertThat(series.get(i).change()).isEqualByComparingTo(expectedChange);
        }
    }

    static Stream<Arguments> seriesChangeCases() {
        List<AnalyticsQueryRow> rows = new ArrayList<>();
        rows.add(holdingRow("AAPL", "10", "200", "USD", "190"));
        for (int i = 6; i >= 0; i--) {
            rows.add(historyRow("AAPL", "USD", daysAgo(i), String.valueOf(190 + i)));
        }
        return Stream.of(Arguments.of(rows));
    }

    // ── Property 6: 24h change formula ──────────────────────────────────────

    @ParameterizedTest
    @MethodSource("change24hCases")
    void property6_change24hFormula(String current, String ago, String expectedPct) {
        BigDecimal result = service.computeChange24hPercent(
                new BigDecimal(current), ago == null ? null : new BigDecimal(ago));
        assertThat(result).isEqualByComparingTo(new BigDecimal(expectedPct));
    }

    static Stream<Arguments> change24hCases() {
        return Stream.of(
                // Equal prices → 0%
                Arguments.of("200", "200", "0"),
                // Doubled → 100%
                Arguments.of("200", "100", "100.0000"),
                // Halved → -50%
                Arguments.of("50", "100", "-50.0000"),
                // price24hAgo = 0 → 0 (no division)
                Arguments.of("200", "0", "0"),
                // price24hAgo = null → 0
                Arguments.of("200", null, "0")
        );
    }

    // ── Property 7: Synthetic series anchor and length ───────────────────────

    @ParameterizedTest
    @MethodSource("syntheticSeriesCases")
    void property7_syntheticSeriesAnchorAndLength(String anchorStr, int days) {
        BigDecimal anchor = new BigDecimal(anchorStr);
        List<PerformancePointDto> series = service.generateSyntheticSeries(anchor, days);

        assertThat(series).hasSize(days);
        assertThat(series.get(series.size() - 1).value()).isEqualByComparingTo(anchor);
        assertThat(series.get(0).change()).isEqualByComparingTo(BigDecimal.ZERO);

        // Entries ordered ascending by date
        for (int i = 1; i < series.size(); i++) {
            assertThat(series.get(i).date()).isGreaterThan(series.get(i - 1).date());
        }

        // Change consistency
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
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any()))
                .thenReturn(rows);
    }

    private static AnalyticsQueryRow holdingRow(String ticker, String qty, String price,
                                                 String currency, String price24h) {
        return new AnalyticsQueryRow(
                "HOLDING", ticker,
                new BigDecimal(qty),
                new BigDecimal(price),
                currency,
                price24h != null ? new BigDecimal(price24h) : null,
                null, null
        );
    }

    private static AnalyticsQueryRow historyRow(String ticker, String currency,
                                                  String date, String price) {
        return new AnalyticsQueryRow(
                "HISTORY", ticker,
                null, null, currency, null,
                date,
                new BigDecimal(price)
        );
    }

    private static String daysAgo(int days) {
        return LocalDate.now().minusDays(days).toString();
    }
}
