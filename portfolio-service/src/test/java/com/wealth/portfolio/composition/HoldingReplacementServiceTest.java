package com.wealth.portfolio.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wealth.portfolio.Portfolio;
import com.wealth.portfolio.PortfolioRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class HoldingReplacementServiceTest {

    @Mock PortfolioRepository portfolioRepository;
    @Mock CompositionCatalogValidator catalogValidator;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock EntityManager entityManager;
    @Mock Clock clock;
    @Mock TuplePreparer preparer;

    @InjectMocks HoldingReplacementService service;

    @Test
    void absentAggregateWithNonZeroExpectedVersionConflictsBeforeValidation() {
        when(portfolioRepository.findByUserId("u1")).thenReturn(List.of());

        assertThatThrownBy(
                        () ->
                                service.replace(
                                        "u1",
                                        7L,
                                        List.of(new RawIntent("AAPL", new BigDecimal("-1"))),
                                        preparer))
                .isInstanceOf(PortfolioVersionConflictException.class)
                .satisfies(
                        ex ->
                                assertThat(
                                                ((PortfolioVersionConflictException) ex)
                                                        .currentVersion())
                                        .hasValue(0L));

        verify(catalogValidator, never()).validate(anyList(), anyList());
        verify(preparer, never()).materialise(anyList(), anyList());
        verify(portfolioRepository, never()).saveAndFlush(any());
    }

    @Test
    void versionMismatchOutranksSemanticAndCatalogFailures() {
        Portfolio portfolio = new Portfolio("u1");
        ReflectionTestUtils.setField(portfolio, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(portfolio, "version", 3L);
        when(portfolioRepository.findByUserId("u1")).thenReturn(List.of(portfolio));

        assertThatThrownBy(
                        () ->
                                service.replace(
                                        "u1",
                                        2L,
                                        List.of(new RawIntent("AAPL", new BigDecimal("-1"))),
                                        preparer))
                .isInstanceOf(PortfolioVersionConflictException.class)
                .satisfies(
                        ex ->
                                assertThat(
                                                ((PortfolioVersionConflictException) ex)
                                                        .currentVersion())
                                        .hasValue(3L));

        verify(catalogValidator, never()).validate(anyList(), anyList());
        verify(preparer, never()).materialise(anyList(), anyList());
        verify(jdbcTemplate, never()).update(anyString(), any(), any(), anyLong());
    }

    @Test
    void quantityOutOfDomainAggregatesBeforeDuplicateCheck() {
        Portfolio portfolio = new Portfolio("u1");
        ReflectionTestUtils.setField(portfolio, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(portfolio, "version", 0L);
        when(portfolioRepository.findByUserId("u1")).thenReturn(List.of(portfolio));

        assertThatThrownBy(
                        () ->
                                service.replace(
                                        "u1",
                                        0L,
                                        List.of(
                                                new RawIntent("AAPL", new BigDecimal("0")),
                                                new RawIntent("AAPL", new BigDecimal("-2")),
                                                new RawIntent("MSFT", BigDecimal.ONE)),
                                        preparer))
                .isInstanceOf(QuantityOutOfDomainException.class)
                .satisfies(
                        ex ->
                                assertThat(((QuantityOutOfDomainException) ex).tickers())
                                        .containsExactly("AAPL"));

        verify(catalogValidator, never()).validate(anyList(), anyList());
    }

    @Test
    void nullQuantityRejectedByQuantityDomainAfterVersionMatch() {
        Portfolio portfolio = new Portfolio("u1");
        ReflectionTestUtils.setField(portfolio, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(portfolio, "version", 0L);
        when(portfolioRepository.findByUserId("u1")).thenReturn(List.of(portfolio));

        assertThatThrownBy(
                        () ->
                                service.replace(
                                        "u1",
                                        0L,
                                        List.of(new RawIntent("AAPL", null)),
                                        preparer))
                .isInstanceOf(QuantityOutOfDomainException.class)
                .satisfies(
                        ex ->
                                assertThat(((QuantityOutOfDomainException) ex).tickers())
                                        .containsExactly("AAPL"));

        verify(catalogValidator, never()).validate(anyList(), anyList());
    }

    @Test
    void duplicateTickersRejectedAfterQuantityPasses() {
        Portfolio portfolio = new Portfolio("u1");
        ReflectionTestUtils.setField(portfolio, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(portfolio, "version", 0L);
        when(portfolioRepository.findByUserId("u1")).thenReturn(List.of(portfolio));

        assertThatThrownBy(
                        () ->
                                service.replace(
                                        "u1",
                                        0L,
                                        List.of(
                                                new RawIntent("AAPL", BigDecimal.ONE),
                                                new RawIntent("MSFT", BigDecimal.TEN),
                                                new RawIntent("AAPL", BigDecimal.TWO)),
                                        preparer))
                .isInstanceOf(DuplicateTickerException.class)
                .satisfies(
                        ex ->
                                assertThat(((DuplicateTickerException) ex).tickers())
                                        .containsExactly("AAPL"));
    }

    @Test
    void noOpSkipsParentCasWhenDesiredEqualsLocked() {
        Portfolio portfolio = new Portfolio("u1");
        ReflectionTestUtils.setField(portfolio, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(portfolio, "version", 5L);
        ReflectionTestUtils.setField(
                portfolio, "createdAt", java.time.Instant.parse("2026-01-01T00:00:00Z"));
        ReflectionTestUtils.setField(
                portfolio, "updatedAt", java.time.Instant.parse("2026-01-02T00:00:00Z"));
        when(portfolioRepository.findByUserId("u1")).thenReturn(List.of(portfolio));
        when(preparer.materialise(eq(List.of()), anyList())).thenReturn(List.of());

        CompositionResult result = service.replace("u1", 5L, List.of(), preparer);

        assertThat(result.noOp()).isTrue();
        assertThat(result.created()).isFalse();
        assertThat(result.version()).isEqualTo(5L);
        verify(jdbcTemplate, never()).update(anyString(), any(), any(), anyLong());
    }
}
