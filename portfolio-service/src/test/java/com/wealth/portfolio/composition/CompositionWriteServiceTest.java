package com.wealth.portfolio.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wealth.portfolio.Portfolio;
import com.wealth.portfolio.PortfolioRepository;
import com.wealth.portfolio.PortfolioResponse;
import com.wealth.portfolio.PortfolioService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CompositionWriteServiceTest {

    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";
    private static final Instant CREATED = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-08-02T00:00:00Z");
    private static final Instant BASIS_AS_OF = Instant.parse("2026-07-01T00:00:00Z");

    @Mock private HoldingReplacementService replacementService;
    @Mock private CompositionTuplePreparer compositionTuplePreparer;
    @Mock private PortfolioRepository portfolioRepository;
    @Mock private PortfolioService portfolioService;

    private CompositionWriteService service;

    @BeforeEach
    void setUp() {
        service =
                new CompositionWriteService(
                        replacementService,
                        compositionTuplePreparer,
                        portfolioRepository,
                        portfolioService);
    }

    @Test
    void replace_delegatesOnceWithMappedIntentsAndInjectedPreparer() {
        UUID portfolioId = UUID.randomUUID();
        UUID holdingId = UUID.randomUUID();
        CompositionHoldingsRequest request =
                new CompositionHoldingsRequest(
                        7L,
                        List.of(
                                new CompositionHoldingsRequest.HoldingIntent(
                                        "AAPL", new BigDecimal("0.75000000")),
                                new CompositionHoldingsRequest.HoldingIntent(
                                        "MSFT", new BigDecimal("2.00000000"))));

        CompositionResult result =
                new CompositionResult(
                        portfolioId,
                        USER_ID,
                        CREATED,
                        UPDATED,
                        8L,
                        List.of(
                                desired("AAPL", "0.75000000"),
                                desired("MSFT", "2.00000000")),
                        false,
                        false);
        Portfolio portfolio = portfolio(portfolioId, USER_ID, 8L);
        PortfolioResponse mapped =
                new PortfolioResponse(
                        portfolioId,
                        USER_ID,
                        CREATED,
                        UPDATED,
                        8L,
                        List.of(
                                new PortfolioResponse.HoldingResponse(
                                        holdingId, "AAPL", new BigDecimal("0.75000000")),
                                new PortfolioResponse.HoldingResponse(
                                        UUID.randomUUID(), "MSFT", new BigDecimal("2.00000000"))));

        when(replacementService.replace(
                        eq(USER_ID), eq(7L), anyList(), same(compositionTuplePreparer)))
                .thenReturn(result);
        when(portfolioRepository.findById(portfolioId)).thenReturn(Optional.of(portfolio));
        when(portfolioService.toPortfolioResponse(portfolio)).thenReturn(mapped);

        CompositionWriteService.Outcome outcome = service.replace(USER_ID, request);

        assertThat(outcome.created()).isFalse();
        assertThat(outcome.response()).isSameAs(mapped);
        assertThat(outcome.response().holdings()).allSatisfy(h -> assertThat(h.id()).isNotNull());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RawIntent>> intentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(replacementService)
                .replace(
                        eq(USER_ID),
                        eq(7L),
                        intentsCaptor.capture(),
                        same(compositionTuplePreparer));
        assertThat(intentsCaptor.getValue())
                .containsExactly(
                        new RawIntent("AAPL", new BigDecimal("0.75000000")),
                        new RawIntent("MSFT", new BigDecimal("2.00000000")));

        InOrder order = inOrder(replacementService, portfolioRepository, portfolioService);
        order.verify(replacementService)
                .replace(eq(USER_ID), eq(7L), anyList(), same(compositionTuplePreparer));
        order.verify(portfolioRepository).findById(portfolioId);
        order.verify(portfolioService).toPortfolioResponse(portfolio);
        verify(portfolioRepository, never()).findByUserId(any());
    }

    @Test
    void replace_createdFlagComesFromReplacementOutcome() {
        UUID portfolioId = UUID.randomUUID();
        CompositionResult result =
                new CompositionResult(
                        portfolioId, USER_ID, CREATED, UPDATED, 1L, List.of(), true, false);
        Portfolio portfolio = portfolio(portfolioId, USER_ID, 1L);
        PortfolioResponse mapped =
                new PortfolioResponse(portfolioId, USER_ID, CREATED, UPDATED, 1L, List.of());

        when(replacementService.replace(
                        eq(USER_ID), eq(0L), anyList(), same(compositionTuplePreparer)))
                .thenReturn(result);
        when(portfolioRepository.findById(portfolioId)).thenReturn(Optional.of(portfolio));
        when(portfolioService.toPortfolioResponse(portfolio)).thenReturn(mapped);

        CompositionWriteService.Outcome outcome =
                service.replace(USER_ID, new CompositionHoldingsRequest(0L, List.of()));

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.response()).isSameAs(mapped);
    }

    @Test
    void replace_rejectsProjectionMismatchWithoutRepair() {
        UUID portfolioId = UUID.randomUUID();
        CompositionResult result =
                new CompositionResult(
                        portfolioId,
                        USER_ID,
                        CREATED,
                        UPDATED,
                        8L,
                        List.of(desired("AAPL", "0.75000000")),
                        false,
                        false);
        Portfolio portfolio = portfolio(portfolioId, USER_ID, 8L);
        PortfolioResponse mismatched =
                new PortfolioResponse(
                        portfolioId,
                        USER_ID,
                        CREATED,
                        UPDATED,
                        9L,
                        List.of(
                                new PortfolioResponse.HoldingResponse(
                                        UUID.randomUUID(),
                                        "AAPL",
                                        new BigDecimal("0.75000000"))));

        when(replacementService.replace(
                        eq(USER_ID), eq(7L), anyList(), same(compositionTuplePreparer)))
                .thenReturn(result);
        when(portfolioRepository.findById(portfolioId)).thenReturn(Optional.of(portfolio));
        when(portfolioService.toPortfolioResponse(portfolio)).thenReturn(mismatched);

        assertThatThrownBy(
                        () ->
                                service.replace(
                                        USER_ID,
                                        new CompositionHoldingsRequest(
                                                7L,
                                                List.of(
                                                        new CompositionHoldingsRequest.HoldingIntent(
                                                                "AAPL",
                                                                new BigDecimal("0.75000000"))))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disagrees");
    }

    @Test
    void replace_propagatesConflictWithoutProjectionLookupOrRetry() {
        when(replacementService.replace(
                        eq(USER_ID), anyLong(), anyList(), same(compositionTuplePreparer)))
                .thenThrow(new PortfolioVersionConflictException(4L));

        assertThatThrownBy(
                        () ->
                                service.replace(
                                        USER_ID, new CompositionHoldingsRequest(3L, List.of())))
                .isInstanceOf(PortfolioVersionConflictException.class);

        verify(replacementService)
                .replace(eq(USER_ID), eq(3L), anyList(), same(compositionTuplePreparer));
        verify(portfolioRepository, never()).findById(any());
        verify(portfolioService, never()).toPortfolioResponse(any());
    }

    private static DesiredHoldingState desired(String ticker, String quantity) {
        return new DesiredHoldingState(
                ticker,
                new BigDecimal(quantity),
                BigDecimal.ONE,
                "USD",
                "LIVE",
                BASIS_AS_OF);
    }

    private static Portfolio portfolio(UUID id, String userId, long version) {
        Portfolio portfolio = new Portfolio(userId);
        ReflectionTestUtils.setField(portfolio, "id", id);
        ReflectionTestUtils.setField(portfolio, "version", version);
        ReflectionTestUtils.setField(portfolio, "createdAt", CREATED);
        ReflectionTestUtils.setField(portfolio, "updatedAt", UPDATED);
        return portfolio;
    }
}
