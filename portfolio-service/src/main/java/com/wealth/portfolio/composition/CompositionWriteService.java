package com.wealth.portfolio.composition;

import com.wealth.portfolio.Portfolio;
import com.wealth.portfolio.PortfolioRepository;
import com.wealth.portfolio.PortfolioResponse;
import com.wealth.portfolio.PortfolioService;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaction-scoped response adapter for the public composition endpoint. Invokes the shared
 * replacement operation once, then projects {@link PortfolioResponse} before the transaction ends.
 */
@Service
public class CompositionWriteService {

    private final HoldingReplacementService replacementService;
    private final CompositionTuplePreparer compositionTuplePreparer;
    private final PortfolioRepository portfolioRepository;
    private final PortfolioService portfolioService;

    public CompositionWriteService(
            HoldingReplacementService replacementService,
            CompositionTuplePreparer compositionTuplePreparer,
            PortfolioRepository portfolioRepository,
            PortfolioService portfolioService) {
        this.replacementService = replacementService;
        this.compositionTuplePreparer = compositionTuplePreparer;
        this.portfolioRepository = portfolioRepository;
        this.portfolioService = portfolioService;
    }

    @Transactional
    public Outcome replace(String userId, CompositionHoldingsRequest request) {
        List<RawIntent> intents =
                request.holdings().stream()
                        .map(h -> new RawIntent(h.ticker(), h.quantity()))
                        .toList();

        CompositionResult result =
                replacementService.replace(
                        userId, request.expectedVersion(), intents, compositionTuplePreparer);

        Portfolio portfolio =
                portfolioRepository
                        .findById(result.portfolioId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Portfolio missing after composition replace: "
                                                        + result.portfolioId()));
        portfolio.getHoldings().size();

        PortfolioResponse response = portfolioService.toPortfolioResponse(portfolio);
        assertProjectionMatches(response, result);
        return new Outcome(response, result.created());
    }

    private static void assertProjectionMatches(PortfolioResponse response, CompositionResult result) {
        if (!Objects.equals(response.id(), result.portfolioId())
                || !Objects.equals(response.userId(), result.userId())
                || response.version() != result.version()
                || !Objects.equals(response.createdAt(), result.createdAt())
                || !Objects.equals(response.updatedAt(), result.updatedAt())
                || !holdingSetsAgree(response, result)) {
            throw new IllegalStateException(
                    "Response projection disagrees with replacement result");
        }
    }

    private static boolean holdingSetsAgree(PortfolioResponse response, CompositionResult result) {
        Map<String, BigDecimal> projected =
                response.holdings().stream()
                        .collect(
                                Collectors.toMap(
                                        PortfolioResponse.HoldingResponse::assetTicker,
                                        PortfolioResponse.HoldingResponse::quantity,
                                        (a, b) -> a,
                                        HashMap::new));
        Map<String, BigDecimal> operation =
                result.holdings().stream()
                        .collect(
                                Collectors.toMap(
                                        DesiredHoldingState::ticker,
                                        DesiredHoldingState::quantity,
                                        (a, b) -> a,
                                        HashMap::new));
        if (projected.size() != operation.size()) {
            return false;
        }
        for (Map.Entry<String, BigDecimal> entry : operation.entrySet()) {
            BigDecimal projectedQty = projected.get(entry.getKey());
            if (projectedQty == null
                    || QuantityDomain.canonicalQuantity(projectedQty)
                                    .compareTo(QuantityDomain.canonicalQuantity(entry.getValue()))
                            != 0) {
                return false;
            }
        }
        return true;
    }

    public record Outcome(PortfolioResponse response, boolean created) {}
}
