package com.wealth.portfolio;

import com.wealth.catalog.SupportedCatalog;
import com.wealth.market.events.PriceUpdatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class MarketPriceProjectionServiceFailureTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private MarketPriceProjectionService projectionService;

    @BeforeEach
    void setUp() {
        projectionService =
                new MarketPriceProjectionService(
                        jdbcTemplate, SupportedCatalog.load(), false, new PriceProjectionSignals());
    }

    @Test
    void databaseFailurePropagatesSynchronously() {
        doThrow(new DataAccessResourceFailureException("db down"))
                .when(jdbcTemplate)
                .update(anyString(), any(), any(), any(), any());

        assertThatThrownBy(
                        () ->
                                projectionService.upsertLatestPrice(
                                        new PriceUpdatedEvent("AAPL", new BigDecimal("1.00"), "USD", null, null, null)))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
