package com.wealth.portfolio;

import com.wealth.portfolio.dto.PortfolioAnalyticsDto;
import com.wealth.portfolio.dto.PortfolioAnalyticsDto.HoldingAnalyticsDto;
import com.wealth.portfolio.freshness.AssetPriceFreshnessProperties;
import com.wealth.portfolio.fx.FxProperties;
import com.wealth.portfolio.seed.SeedTickerRegistry;
import com.wealth.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Rehearsal defect #2 (F5): a holding whose price is older than the freshness threshold (the same
 * 50 h rule as the summary banner) has no 24h change — its price did not move because it was not
 * observed. Every change field is null with basis {@code STALE_PRICE}, so it also leaves the 24h
 * totals (marked partial) and best/worst performer. A FRESH price with a genuine zero move keeps
 * 0.00, and UNKNOWN / MISSING keep today's behaviour.
 */
class PortfolioAnalyticsStalePriceTest {

    private static final String USER_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final Duration THRESHOLD = AssetPriceFreshnessProperties.defaults().threshold();

    private JdbcTemplate jdbcTemplate;
    private PortfolioAnalyticsService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        UserRepository users = mock(UserRepository.class);
        PortfolioRepository portfolios = mock(PortfolioRepository.class);
        FxProperties fx = mock(FxProperties.class);
        SeedTickerRegistry registry = mock(SeedTickerRegistry.class);
        when(fx.baseCurrency()).thenReturn("USD");
        when(users.existsById(UUID.fromString(USER_ID))).thenReturn(true);
        when(portfolios.existsByUserId(USER_ID)).thenReturn(true);
        when(registry.find(anyString())).thenReturn(Optional.empty());
        service = new PortfolioAnalyticsService(jdbcTemplate, users, portfolios, mock(FxRateProvider.class),
                fx, registry, AssetPriceFreshnessProperties.defaults());
    }

    @Test
    void stalePrice_nullsEveryChangeField_andSaysWhy() {
        Instant observed = Instant.now().minus(THRESHOLD).minus(Duration.ofHours(1));
        stub(List.of(row("MATIC-USD", "28", "0.22", "0.22", observed)));

        HoldingAnalyticsDto h = only(service.getAnalytics(USER_ID));

        assertThat(h.priceFreshness()).isEqualTo("STALE");
        assertThat(h.change24hPercent()).isNull();
        assertThat(h.change24hAbsolute()).isNull();
        assertThat(h.change24hValueBase()).isNull();
        assertThat(h.change24hReferenceAt()).isNull();
        assertThat(h.changeBasis()).isEqualTo("STALE_PRICE");
        assertThat(h.priceObservedAt()).isEqualTo(observed.toString());
        // Value is still reported, at the last known price.
        assertThat(h.currentValueBase()).isEqualByComparingTo("6.16");
    }

    @Test
    void freshPrice_withAGenuineZeroMove_keepsZero() {
        Instant observed = Instant.now().minus(THRESHOLD).plus(Duration.ofMinutes(5));
        stub(List.of(row("AAPL", "10", "200.00", "200.00", observed)));

        HoldingAnalyticsDto h = only(service.getAnalytics(USER_ID));

        assertThat(h.priceFreshness()).isEqualTo("FRESH");
        assertThat(h.change24hPercent()).isEqualByComparingTo("0");
        assertThat(h.change24hValueBase()).isEqualByComparingTo("0");
        assertThat(h.changeBasis()).isEqualTo("WITHIN_24H_WINDOW");
    }

    @Test
    void unknownObservationTime_keepsTodaysBehaviour() {
        stub(List.of(row("AAPL", "10", "200.00", "190.00", null)));

        HoldingAnalyticsDto h = only(service.getAnalytics(USER_ID));

        assertThat(h.priceFreshness()).isEqualTo("UNKNOWN");
        assertThat(h.change24hPercent()).isNotNull();
        assertThat(h.changeBasis()).isEqualTo("WITHIN_24H_WINDOW");
    }

    @Test
    void missingPrice_isMissing_withNoChange() {
        stub(List.of(row("GONE", "1", null, null, null)));

        HoldingAnalyticsDto h = only(service.getAnalytics(USER_ID));

        assertThat(h.priceFreshness()).isEqualTo("MISSING");
        assertThat(h.change24hPercent()).isNull();
    }

    @Test
    void staleHolding_leavesTheTotalsAndPerformers_andMarksThemPartial() {
        Instant fresh = Instant.now().minus(Duration.ofHours(3));
        Instant stale = Instant.now().minus(THRESHOLD).minus(Duration.ofDays(5));
        stub(List.of(
                row("AAPL", "10", "210.00", "200.00", fresh),          // +5%, +100 base
                row("MSFT", "10", "400.00", "404.00", fresh),          // -0.99%, -40 base
                row("FTM-USD", "3", "0.70", "0.35", stale)));          // would be +100% if not stale

        PortfolioAnalyticsDto result = service.getAnalytics(USER_ID);

        assertThat(result.totalChange24hBase()).isEqualByComparingTo("60.0000");
        assertThat(result.change24hCoverage().holdingsWithChange()).isEqualTo(2);
        assertThat(result.change24hCoverage().partial()).isTrue();
        assertThat(result.bestPerformer().ticker()).isEqualTo("AAPL");
        assertThat(result.worstPerformer().ticker()).isEqualTo("MSFT");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void stub(List<AnalyticsQueryRow> rows) {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(rows);
    }

    private static HoldingAnalyticsDto only(PortfolioAnalyticsDto dto) {
        assertThat(dto.holdings()).hasSize(1);
        return dto.holdings().get(0);
    }

    /** A USD holding with an in-window 24h reference, observed at {@code observedAt}. */
    private static AnalyticsQueryRow row(String ticker, String qty, String price, String ref, Instant observedAt) {
        return new AnalyticsQueryRow(
                "HOLDING", ticker, new BigDecimal(qty),
                price != null ? new BigDecimal(price) : null,
                price != null ? "USD" : null,
                ref != null ? new BigDecimal(ref) : null,
                ref != null ? Instant.now().minus(Duration.ofHours(24)) : null,
                ref != null ? "WITHIN_24H_WINDOW" : null,
                null, null, null, null,
                observedAt);
    }
}
