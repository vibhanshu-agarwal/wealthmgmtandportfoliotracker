package com.wealth.portfolio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.stream.Stream;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class PortfolioControllerTest {

    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";

    @Mock
    PortfolioService portfolioService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PortfolioController(portfolioService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // 19.1: GET /api/portfolio with X-User-Id header → 200 with portfolio list
    @Test
    void getPortfoliosWithValidHeaderReturns200() throws Exception {
        when(portfolioService.getByUserId(USER_ID)).thenReturn(List.of());

        mockMvc.perform(get("/api/portfolio")
                        .header("X-User-Id", USER_ID))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    // 19.1: GET /api/portfolio with missing X-User-Id header → 400 with structured error body
    @Test
    void getPortfoliosWithMissingHeaderReturns400() throws Exception {
        mockMvc.perform(get("/api/portfolio"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Required header 'X-User-Id' is missing"));
    }

    // 19.1: GET /api/portfolio with unknown UUID in X-User-Id → 404
    @Test
    void getPortfoliosWithUnknownUserReturns404() throws Exception {
        String unknownId = "ffffffff-ffff-ffff-ffff-ffffffffffff";
        when(portfolioService.getByUserId(unknownId)).thenThrow(new UserNotFoundException(unknownId));

        mockMvc.perform(get("/api/portfolio")
                        .header("X-User-Id", unknownId))
                .andExpect(status().isNotFound());
    }

    // 19.3: Property 7 — Missing X-User-Id header always returns 400
    static Stream<String> portfolioEndpoints() {
        return Stream.of("/api/portfolio");
    }

    @ParameterizedTest(name = "missing X-User-Id on {0} returns 400")
    @MethodSource("portfolioEndpoints")
    void missingHeaderAlwaysReturns400(String endpoint) throws Exception {
        mockMvc.perform(get(endpoint))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }
}
