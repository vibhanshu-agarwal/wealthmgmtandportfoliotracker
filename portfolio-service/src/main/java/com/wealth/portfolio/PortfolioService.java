package com.wealth.portfolio;

import com.wealth.portfolio.dto.PortfolioSummaryDto;
import com.wealth.user.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class PortfolioService {

    private final PortfolioRepository portfolioRepository;
    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;

    public PortfolioService(PortfolioRepository portfolioRepository,
                            JdbcTemplate jdbcTemplate,
                            UserRepository userRepository) {
        this.portfolioRepository = portfolioRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<PortfolioResponse> getByUserId(String userId) {
        requireUserExists(userId);
        return portfolioRepository.findByUserId(userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PortfolioSummaryDto getSummary(String userId) {
        requireUserExists(userId);
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
        // TODO: Add currency conversion + FX handling for multi-currency portfolios.

        return new PortfolioSummaryDto(
                userId,
                portfolios.size(),
                totalHoldings,
                totalValue == null ? BigDecimal.ZERO : totalValue
        );
    }

    private void requireUserExists(String userId) {
        try {
            UUID uuid = UUID.fromString(userId);
            if (!userRepository.existsById(uuid)) {
                throw new UserNotFoundException(userId);
            }
        } catch (IllegalArgumentException ex) {
            throw new UserNotFoundException(userId);
        }
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
