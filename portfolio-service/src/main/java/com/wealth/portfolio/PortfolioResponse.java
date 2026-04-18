package com.wealth.portfolio;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only projection returned by the Portfolio REST API.
 *
 * <p>This record is the only type that crosses the HTTP boundary — JPA entities are
 * never serialised directly, keeping the API contract decoupled from the persistence model.
 *
 * @param id       portfolio identifier
 * @param userId   owner identifier (plain string — no JPA association to the user module)
 * @param createdAt when the portfolio was created
 * @param holdings  the asset holdings at the time of the request
 */
public record PortfolioResponse(
        UUID id,
        String userId,
        Instant createdAt,
        List<HoldingResponse> holdings
) {

    /**
     * A single asset holding within the portfolio.
     *
     * @param id          holding identifier
     * @param assetTicker the asset ticker symbol (e.g. "AAPL", "BTC")
     * @param quantity    the number of units held
     */
    public record HoldingResponse(
            UUID id,
            String assetTicker,
            BigDecimal quantity
    ) {}
}
