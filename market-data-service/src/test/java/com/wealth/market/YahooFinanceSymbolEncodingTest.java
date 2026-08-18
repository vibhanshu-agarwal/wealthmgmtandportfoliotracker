package com.wealth.market;

import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponentsBuilder;

import static org.assertj.core.api.Assertions.assertThat;

class YahooFinanceSymbolEncodingTest {

    @Test
    void ampersandTickerIsUrlEncodedForYahooQuoteRequest() {
        String uri =
                UriComponentsBuilder.fromPath("/v7/finance/quote")
                        .queryParam("symbols", "M&M.NS")
                        .build()
                        .encode()
                        .toUriString();

        assertThat(uri).contains("M%26M.NS");
        assertThat(uri).doesNotContain("M&M.NS");
    }
}
