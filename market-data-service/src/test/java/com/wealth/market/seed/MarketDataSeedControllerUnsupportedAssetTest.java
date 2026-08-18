package com.wealth.market.seed;

import com.wealth.catalog.UnsupportedAssetException;
import com.wealth.market.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class MarketDataSeedControllerUnsupportedAssetTest {

    @Mock private MarketDataSeedService seedService;

    @Test
    void seedUnsupportedAssetReturns422Contract() throws Exception {
        when(seedService.seed(anyString()))
                .thenThrow(new UnsupportedAssetException("FAKE", "c3dcb95e4e09212a"));

        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new MarketDataSeedController(seedService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(post("/api/internal/market-data/seed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"e2e-user\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("unsupported_asset"))
                .andExpect(jsonPath("$.ticker").value("FAKE"))
                .andExpect(jsonPath("$.catalogVersion").value("c3dcb95e4e09212a"));
    }
}
