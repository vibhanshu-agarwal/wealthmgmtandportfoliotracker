package com.wealth.portfolio;

import com.wealth.portfolio.dto.PortfolioSummaryDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class PortfolioService {

    private final PortfolioRepository portfolioRepository;
    private final JdbcTemplate jdbcTemplate;

    public PortfolioService(PortfolioRepository portfolioRepository, JdbcTemplate jdbcTemplate) {
        this.portfolioRepository = portfolioRepository;
        this.jdbcTemplate = jdbcTemplate;
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

        var totalValue = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(SUM(h.quantity * COALESCE(mp.current_price, 0)), 0)
                FROM asset_holdings h
                JOIN portfolios p ON p.id = h.portfolio_id
                LEFT JOIN market_prices mp ON mp.ticker = h.asset_ticker
                WHERE p.user_id = ?
                """,
                BigDecimal.class,
                userId
        );

        return new PortfolioSummaryDto(
                userId,
                portfolios.size(),
                totalHoldings,
                totalValue == null ? BigDecimal.ZERO : totalValue
        );
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
