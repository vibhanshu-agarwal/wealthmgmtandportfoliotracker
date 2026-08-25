package com.wealth.portfolio.seed;

import com.wealth.portfolio.PortfolioRepository;

import com.wealth.catalog.UnsupportedAssetException;
import com.wealth.portfolio.GlobalExceptionHandler;
import com.wealth.portfolio.seed.PortfolioSeedService.SeedResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import static org.mockito.Mockito.mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract test for the internal seed endpoint's response body.
 *
 * <p>The endpoint is reachable in production and is invoked there on a schedule. Its response
 * previously carried {@code marketPricesUpserted}, a count of rows the seeder upserted into the
 * global {@code market_prices} table — a write that overwrote live refreshed prices for every
 * user. The field is asserted <em>absent</em> rather than zero: a caller that still reads it
 * should fail loudly rather than silently observe a plausible-looking {@code 0}.
 *
 * <p>This is the fast counterpart to {@code PortfolioSeedServiceIT}, which proves against a real
 * database that neither price table is modified. Here we only pin the wire contract.
 */
@ExtendWith(MockitoExtension.class)
class PortfolioSeedControllerTest {

    @Mock private PortfolioSeedService seedService;

    @Test
    void seedResponse_carriesHoldingsOnly_andNeverAMarketDataCount() {
        UUID portfolioId = UUID.randomUUID();
        when(seedService.seed(anyString())).thenReturn(new SeedResult(portfolioId, 160));

        ResponseEntity<Map<String, Object>> response = new PortfolioSeedController(seedService).seed();

        assertThat(response.getStatusCode().value()).isEqualTo(200);

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();

        assertThat(body)
                .as("seed response must expose exactly these keys — no market-data count")
                .containsOnlyKeys("userId", "portfolioId", "holdingsInserted");

        assertThat(body)
                .as("marketPricesUpserted must be absent, not zero: portfolio-service must "
                        + "never write market data, so there is no count to report")
                .doesNotContainKey("marketPricesUpserted");

        assertThat(body.get("portfolioId")).isEqualTo(portfolioId.toString());
        assertThat(body.get("holdingsInserted")).isEqualTo(160);
    }

    @Test
    void seedUnsupportedAssetReturns422Contract() throws Exception {
        when(seedService.seed(anyString()))
                .thenThrow(new UnsupportedAssetException("TATAMOTORS.NS", "c3dcb95e4e09212a"));

        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new PortfolioSeedController(seedService))
                .setControllerAdvice(new GlobalExceptionHandler(mock(PortfolioRepository.class)))
                .build();

        mockMvc.perform(post("/api/internal/portfolio/seed"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("unsupported_asset"))
                .andExpect(jsonPath("$.ticker").value("TATAMOTORS.NS"))
                .andExpect(jsonPath("$.catalogVersion").value("c3dcb95e4e09212a"));
    }
}
