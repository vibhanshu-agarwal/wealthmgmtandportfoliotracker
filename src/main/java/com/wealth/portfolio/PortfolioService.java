package com.wealth.portfolio;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
