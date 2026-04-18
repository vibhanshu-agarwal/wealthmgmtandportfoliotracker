package com.wealth.market;

import java.math.BigDecimal;
import java.time.Instant;

public record MarketPriceDto(
        String ticker,
        BigDecimal currentPrice,
        Instant updatedAt
) {
}
