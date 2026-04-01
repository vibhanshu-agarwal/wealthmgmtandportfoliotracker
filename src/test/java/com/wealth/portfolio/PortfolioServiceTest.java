package com.wealth.portfolio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortfolioServiceTest {

    @Mock
    private PortfolioRepository portfolioRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private PortfolioService portfolioService;

    @Test
    void shouldReturnPortfolioSummaryForUser() {
        var first = new Portfolio("user-001");
        var second = new Portfolio("user-001");

        when(portfolioRepository.findByUserId("user-001")).thenReturn(List.of(first, second));
        when(jdbcTemplate.queryForObject(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(BigDecimal.class),
                org.mockito.ArgumentMatchers.eq("user-001"))).thenReturn(BigDecimal.ZERO);

        var summary = portfolioService.getSummary("user-001");

        assertEquals("user-001", summary.userId());
        assertEquals(2, summary.portfolioCount());
        assertEquals(0, summary.totalHoldings());
        assertEquals(BigDecimal.ZERO, summary.totalValue());
    }
}
