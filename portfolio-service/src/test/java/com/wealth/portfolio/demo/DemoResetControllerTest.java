package com.wealth.portfolio.demo;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wealth.portfolio.GlobalExceptionHandler;
import com.wealth.portfolio.PortfolioRepository;
import com.wealth.portfolio.PortfolioResponse;
import com.wealth.portfolio.composition.PortfolioVersionConflictException;
import com.wealth.portfolio.seed.InternalApiKeyFilter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class DemoResetControllerTest {

    private static final String KEY = "test-internal-key";
    private static final String PATH = "/api/internal/portfolio/demo-reset";

    @Mock private DemoResetService demoResetService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.standaloneSetup(new DemoResetController(demoResetService))
                        .addFilter(new InternalApiKeyFilter(KEY))
                        .setControllerAdvice(new GlobalExceptionHandler(mock(PortfolioRepository.class)))
                        .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT"})
    void bothVerbsReachServiceWithBodyExpectedVersion(String method) throws Exception {
        UUID portfolioId = UUID.randomUUID();
        PortfolioResponse response =
                new PortfolioResponse(
                        portfolioId,
                        DemoResetService.DEMO_USER_ID,
                        Instant.parse("2020-01-01T00:00:00Z"),
                        Instant.parse("2020-01-02T00:00:00Z"),
                        8L,
                        List.of());
        when(demoResetService.reset(8L)).thenReturn(response);

        var request =
                ("POST".equals(method) ? post(PATH) : put(PATH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Internal-Api-Key", KEY)
                        .content("{\"expectedVersion\":8}");

        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(8))
                .andExpect(jsonPath("$.id").value(portfolioId.toString()));

        verify(demoResetService, times(1)).reset(8L);
    }

    @Test
    void wrongApiKeyRejectedBeforeService() throws Exception {
        mockMvc.perform(
                        post(PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-Internal-Api-Key", "wrong-key")
                                .content("{\"expectedVersion\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("invalid_internal_api_key"));

        verify(demoResetService, never()).reset(anyLong());
    }

    @Test
    void missingExpectedVersionRejectedBeforeService() throws Exception {
        mockMvc.perform(
                        post(PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-Internal-Api-Key", KEY)
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("missing_version"));

        verify(demoResetService, never()).reset(anyLong());
    }

    @Test
    void missingApiKeyRejectedBeforeService() throws Exception {
        mockMvc.perform(
                        post(PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedVersion\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("invalid_internal_api_key"));

        verify(demoResetService, never()).reset(anyLong());
    }

    @Test
    void blankConfiguredKeyFailsClosedWith503() throws Exception {
        MockMvc unconfigured =
                MockMvcBuilders.standaloneSetup(new DemoResetController(demoResetService))
                        .addFilter(new InternalApiKeyFilter(""))
                        .setControllerAdvice(new GlobalExceptionHandler(mock(PortfolioRepository.class)))
                        .build();

        unconfigured
                .perform(
                        post(PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-Internal-Api-Key", KEY)
                                .content("{\"expectedVersion\":1}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("internal_api_key_not_configured"));

        verify(demoResetService, never()).reset(anyLong());
    }

    @Test
    void invalidExpectedVersionUsesContractError() throws Exception {
        mockMvc.perform(
                        post(PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-Internal-Api-Key", KEY)
                                .content("{\"expectedVersion\":\"3\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_version"));

        verify(demoResetService, never()).reset(anyLong());
    }

    @Test
    void staleVersionPropagates409Envelope() throws Exception {
        when(demoResetService.reset(2L)).thenThrow(new PortfolioVersionConflictException(7L));

        mockMvc.perform(
                        post(PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-Internal-Api-Key", KEY)
                                .content("{\"expectedVersion\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("portfolio_version_conflict"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.currentVersion").value(7));
    }
}
