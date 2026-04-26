package com.wealth.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

class MarketPriceControllerTest {

    @Test
    void health_returnsServiceStatus() {
        var controller = new MarketPriceController(
                mock(AssetPriceRepository.class),
                mock(MarketPriceService.class));

        var response = controller.health();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody())
                .containsEntry("status", "UP")
                .containsEntry("service", "market-data-service");
    }
}
