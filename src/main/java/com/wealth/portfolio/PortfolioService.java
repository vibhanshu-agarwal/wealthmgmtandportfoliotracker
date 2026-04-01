package com.wealth.portfolio;

import com.wealth.portfolio.dto.PortfolioSummaryDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class PortfolioService {

    private final PortfolioRepository portfolioRepository;

    public PortfolioService(PortfolioRepository portfolioRepository) {
        this.portfolioRepository = portfolioRepository;
    }

    /**
     * Returns all portfolios owned by the given user, with their holdings.
     *
     * <p>{@code readOnly = true} lets the JPA provider skip dirty-checking and use
     * a read-optimised flush mode, reducing overhead on pure query paths.
     *
     * @param userId the owner's ID
     * @return list of portfolio responses; empty if the user has no portfolios
     */
    @Transactional(readOnly = true)
    public List<PortfolioResponse> getByUserId(String userId) {
        return portfolioRepository.findByUserId(userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Returns a lightweight summary used by the UI "Portfolio Total" widget.
     *
     * <p>The current value is derived from holding quantities only. Price-based valuation
     * is intentionally deferred to the dedicated valuation phase.
     */
    @Transactional(readOnly = true)
    public PortfolioSummaryDto getSummary(String userId) {
        var portfolios = portfolioRepository.findByUserId(userId);
        var totalHoldings = portfolios.stream()
                .mapToInt(p -> p.getHoldings().size())
                .sum();

        var totalValue = portfolios.stream()
                .flatMap(p -> p.getHoldings().stream())
                .map(AssetHolding::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new PortfolioSummaryDto(userId, portfolios.size(), totalHoldings, totalValue);
    }

    private PortfolioResponse toResponse(Portfolio portfolio) {
        var holdings = portfolio.getHoldings().stream()
                .map(h -> new PortfolioResponse.HoldingResponse(
                        h.getId(),
                        h.getAssetTicker(),
                        h.getQuantity()))
                .toList();

        return new PortfolioResponse(
                portfolio.getId(),
                portfolio.getUserId(),
                portfolio.getCreatedAt(),
                holdings);
    }
}
