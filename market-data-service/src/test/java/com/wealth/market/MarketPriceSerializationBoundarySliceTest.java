package com.wealth.market;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Jackson 3 serialization boundary slice test (Task 6.6 / Property 11).
 *
 * <p>Asserts the autoconfigured {@link JsonMapper} bean backs HTTP serialization for
 * {@link MarketPriceController} — including {@link Instant} fields as ISO-8601 on the wire.
 * Kafka producer shape is covered separately in Task 6.5.
 */
@WebMvcTest(MarketPriceController.class)
class MarketPriceSerializationBoundarySliceTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-06-08T10:15:30Z");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JsonMapper jsonMapper;

    @MockitoBean
    AssetPriceRepository assetPriceRepository;

    @MockitoBean
    MarketPriceService marketPriceService;

    @Test
    void autoconfiguredMapper_isJackson3JsonMapper() {
        assertThat(jsonMapper.getClass().getName()).startsWith("tools.jackson.");
        assertThat(jsonMapper.getClass().getName()).doesNotContain("com.fasterxml.jackson");
    }

    @Test
    void priceResponse_serializesInstantAsIso8601ViaAutoconfiguredMapper() throws Exception {
        AssetPrice aapl = new AssetPrice("AAPL", new BigDecimal("195.00"));
        aapl.setQuoteCurrency("USD");
        aapl.setUpdatedAt(OBSERVED_AT);

        when(assetPriceRepository.findByTickerIn(List.of("AAPL"))).thenReturn(List.of(aapl));

        mockMvc.perform(get("/api/market/prices").param("tickers", "AAPL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ticker").value("AAPL"))
                .andExpect(jsonPath("$[0].currentPrice").value(195.00))
                .andExpect(jsonPath("$[0].quoteCurrency").value("USD"))
                .andExpect(jsonPath("$[0].observedAt").value("2026-06-08T10:15:30Z"));
    }
}
