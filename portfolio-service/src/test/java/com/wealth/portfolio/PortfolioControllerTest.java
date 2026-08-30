package com.wealth.portfolio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import static org.mockito.Mockito.mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
                .setControllerAdvice(new GlobalExceptionHandler(mock(PortfolioRepository.class)))
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

    @Test
    void authenticatedPortfolioReadReturnsPersistedVersion() throws Exception {
        PortfolioResponse response = new PortfolioResponse(
                UUID.fromString("00000000-0000-0000-0000-000000000123"),
                USER_ID,
                Instant.parse("2026-08-26T00:00:00Z"),
                Instant.parse("2026-08-27T00:00:00Z"),
                7L,
                List.of());
        when(portfolioService.getByUserId(USER_ID)).thenReturn(List.of(response));

        mockMvc.perform(get("/api/portfolio").header("X-User-Id", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("00000000-0000-0000-0000-000000000123"))
                .andExpect(jsonPath("$[0].userId").value(USER_ID))
                .andExpect(jsonPath("$[0].version").value(7));
    }

    // B2 Task 8.1: GET /api/portfolio serializes updatedAt (and createdAt, for shape equivalence)
    // on every element of a multi-element response, through the real controller/MockMvc pipeline.
    @Test
    void getPortfoliosReturnsCreatedAtAndUpdatedAtForEveryElement() throws Exception {
        Instant createdAt0 = Instant.parse("2026-08-20T10:15:30Z");
        Instant updatedAt0 = Instant.parse("2026-08-28T09:00:00Z");
        Instant createdAt1 = Instant.parse("2026-08-21T11:16:31Z");
        Instant updatedAt1 = Instant.parse("2026-08-29T14:45:12Z");

        PortfolioResponse first = new PortfolioResponse(
                UUID.fromString("00000000-0000-0000-0000-000000000201"),
                USER_ID,
                createdAt0,
                updatedAt0,
                11L,
                List.of());
        PortfolioResponse second = new PortfolioResponse(
                UUID.fromString("00000000-0000-0000-0000-000000000202"),
                USER_ID,
                createdAt1,
                updatedAt1,
                12L,
                List.of());
        when(portfolioService.getByUserId(USER_ID)).thenReturn(List.of(first, second));

        mockMvc.perform(get("/api/portfolio").header("X-User-Id", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].createdAt").value(createdAt0.toString()))
                .andExpect(jsonPath("$[0].updatedAt").value(updatedAt0.toString()))
                .andExpect(jsonPath("$[1].createdAt").value(createdAt1.toString()))
                .andExpect(jsonPath("$[1].updatedAt").value(updatedAt1.toString()));
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
