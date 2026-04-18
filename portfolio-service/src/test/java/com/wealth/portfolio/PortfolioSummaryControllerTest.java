package com.wealth.portfolio;

import com.wealth.portfolio.dto.PortfolioSummaryDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.stream.Stream;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class PortfolioSummaryControllerTest {

    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";

    @Mock
    PortfolioService portfolioService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PortfolioSummaryController(portfolioService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // 19.2: GET /api/portfolio/summary with X-User-Id header → 200 with summary DTO
    @Test
    void getSummaryWithValidHeaderReturns200() throws Exception {
        var summary = new PortfolioSummaryDto(USER_ID, 1, 3, BigDecimal.valueOf(10000), "USD");
        when(portfolioService.getSummary(USER_ID)).thenReturn(summary);

        mockMvc.perform(get("/api/portfolio/summary")
                        .header("X-User-Id", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(USER_ID));
    }

    // 19.2: missing X-User-Id header → 400
    @Test
    void getSummaryWithMissingHeaderReturns400() throws Exception {
        mockMvc.perform(get("/api/portfolio/summary"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Required header 'X-User-Id' is missing"));
    }

    // 19.3: Property 7 — Missing X-User-Id header always returns 400
    static Stream<String> summaryEndpoints() {
        return Stream.of("/api/portfolio/summary");
    }

    @ParameterizedTest(name = "missing X-User-Id on {0} returns 400")
    @MethodSource("summaryEndpoints")
    void missingHeaderAlwaysReturns400(String endpoint) throws Exception {
        mockMvc.perform(get(endpoint))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }
}
