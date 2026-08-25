package com.wealth.portfolio;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PortfolioResponseVersionTest {

    @Test
    void portfolioResponseIncludesVersionComponent() {
        RecordComponent[] components = PortfolioResponse.class.getRecordComponents();
        assertThat(Arrays.stream(components).map(RecordComponent::getName))
                .contains("version");
    }

    @Test
    void holdingQuantitySerializesAsPlainDecimalString() throws Exception {
        var holding = new PortfolioResponse.HoldingResponse(
                UUID.randomUUID(), "AAPL", new BigDecimal("0.75000000"));
        var portfolio = new PortfolioResponse(
                UUID.randomUUID(),
                "user-1",
                Instant.parse("2026-08-25T00:00:00Z"),
                3L,
                List.of(holding));

        String json = JsonMapper.builder().build().writeValueAsString(portfolio);

        assertThat(json).contains("\"version\":3");
        assertThat(json).contains("\"quantity\":\"0.75000000\"");
        assertThat(json).doesNotContain("\"quantity\":0.75");
    }

    @Test
    void holdingQuantityUsesToPlainStringSerializer() throws Exception {
        JsonSerialize annotation =
                PortfolioResponse.HoldingResponse.class
                        .getDeclaredField("quantity")
                        .getAnnotation(JsonSerialize.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.using().getName())
                .isEqualTo("com.wealth.portfolio.composition.ToPlainStringSerializer");
    }
}
