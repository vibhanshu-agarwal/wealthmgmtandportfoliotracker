package com.wealth.portfolio;

import com.wealth.portfolio.fx.FxProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rehearsal defect #4 (F2): rates into the base currency for the Asset Picker estimates, from the
 * shared FxRateProvider; unavailable is null, never 1 (dashboard-data-accuracy Req 5.3).
 */
class FxRatesControllerTest {

    private static final String USER = "00000000-0000-0000-0000-000000000e2e";

    private FxRateProvider provider;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        provider = mock(FxRateProvider.class);
        FxProperties props = new FxProperties("USD", null, null, null);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new FxRatesController(provider, props))
                .setControllerAdvice(new GlobalExceptionHandler(mock(PortfolioRepository.class)))
                .build();
    }

    @Test
    void returnsTheProvidersRate_andExactlyOneForTheBaseCurrency() throws Exception {
        when(provider.getRate("INR", "USD")).thenReturn(new BigDecimal("0.010436"));

        mockMvc.perform(get("/api/portfolio/fx-rates").param("currencies", "INR,USD").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseCurrency").value("USD"))
                .andExpect(jsonPath("$.rates.INR").value(0.010436))
                .andExpect(jsonPath("$.rates.USD").value(1))
                .andExpect(jsonPath("$", aMapWithSize(2)));
        verify(provider, never()).getRate("USD", "USD");
    }

    @Test
    void anUnavailableRate_isNull_neverOne() throws Exception {
        when(provider.getRate("JPY", "USD")).thenThrow(new FxRateUnavailableException("JPY", "USD", null));

        mockMvc.perform(get("/api/portfolio/fx-rates").param("currencies", "JPY").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rates.JPY").value((Object) null));
    }

    @Test
    void malformedCodes_areNull_andNeverReachTheProvider() throws Exception {
        mockMvc.perform(get("/api/portfolio/fx-rates").param("currencies", "inr,US,XYZW,1NR").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rates.inr").value((Object) null))
                .andExpect(jsonPath("$.rates.US").value((Object) null))
                .andExpect(jsonPath("$.rates.XYZW").value((Object) null))
                .andExpect(jsonPath("$.rates.1NR").value((Object) null));
        verify(provider, never()).getRate(anyString(), anyString());
    }

    @Test
    void noCurrencies_returnsAnEmptyMap() throws Exception {
        mockMvc.perform(get("/api/portfolio/fx-rates").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rates", aMapWithSize(0)));
    }

    @Test
    void tooManyCurrencies_isRejected() throws Exception {
        String many = IntStream.range(0, FxRatesController.MAX_CURRENCIES + 1)
                .mapToObj(i -> "C" + (char) ('A' + i / 26) + (char) ('A' + i % 26))
                .collect(Collectors.joining(","));

        mockMvc.perform(get("/api/portfolio/fx-rates").param("currencies", many).header("X-User-Id", USER))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingUserHeader_isRejected() throws Exception {
        mockMvc.perform(get("/api/portfolio/fx-rates").param("currencies", "INR"))
                .andExpect(status().isBadRequest());
    }
}
