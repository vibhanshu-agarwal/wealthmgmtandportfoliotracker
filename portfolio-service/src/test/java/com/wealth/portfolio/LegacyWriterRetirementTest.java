package com.wealth.portfolio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import static org.mockito.Mockito.mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class LegacyWriterRetirementTest {

    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";
    private static final UUID PORTFOLIO_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

    @Mock
    PortfolioService portfolioService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PortfolioController(portfolioService))
                .setControllerAdvice(new GlobalExceptionHandler(mock(PortfolioRepository.class)))
                .build();
    }

    @Test
    void postPortfolioReturns405() throws Exception {
        mockMvc.perform(post("/api/portfolio")
                        .header("X-User-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(status().reason("Method 'POST' is not supported."))
                .andExpect(header().string("Allow", "GET"))
                .andExpect(content().string(""));
    }

    @Test
    void postVersionlessHoldingsReturns404() throws Exception {
        mockMvc.perform(post("/api/portfolio/{portfolioId}/holdings", PORTFOLIO_ID)
                        .header("X-User-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticker\":\"AAPL\",\"quantity\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(status().reason(
                        "No endpoint POST /api/portfolio/" + PORTFOLIO_ID + "/holdings."))
                .andExpect(content().string(""));
    }
}
