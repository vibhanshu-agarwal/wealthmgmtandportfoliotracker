package com.wealth.portfolio;

import com.wealth.portfolio.dto.PortfolioSummaryDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Jackson 3 serialization boundary slice test (Task 6.3 / Property 11).
 *
 * <p>Asserts the autoconfigured {@link JsonMapper} bean — not a stray Jackson 2 mapper — backs
 * MVC request/response serialization for {@link PortfolioSummaryController}.
 */
@WebMvcTest(PortfolioSummaryController.class)
@Import({GlobalExceptionHandler.class, PortfolioSerializationBoundarySliceTest.CacheTestConfig.class})
class PortfolioSerializationBoundarySliceTest {

    @TestConfiguration
    static class CacheTestConfig {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager();
        }
    }

    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JsonMapper jsonMapper;

    @MockitoBean
    PortfolioService portfolioService;

    @Test
    void autoconfiguredMapper_isJackson3JsonMapper() {
        assertThat(jsonMapper.getClass().getName()).startsWith("tools.jackson.");
        assertThat(jsonMapper.getClass().getName()).doesNotContain("com.fasterxml.jackson");
    }

    @Test
    void summaryResponse_serializesBigDecimalViaAutoconfiguredMapper() throws Exception {
        var summary = new PortfolioSummaryDto(
                USER_ID, 2, 5, new BigDecimal("12345.67"), "USD", true);
        when(portfolioService.getSummary(USER_ID)).thenReturn(summary);

        mockMvc.perform(get("/api/portfolio/summary").header("X-User-Id", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(USER_ID))
                .andExpect(jsonPath("$.portfolioCount").value(2))
                .andExpect(jsonPath("$.totalHoldings").value(5))
                .andExpect(jsonPath("$.totalValue").value(12345.67))
                .andExpect(jsonPath("$.baseCurrency").value("USD"))
                .andExpect(jsonPath("$.partialValuation").value(true));
    }
}
