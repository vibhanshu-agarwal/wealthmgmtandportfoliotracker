package com.wealth.portfolio.composition;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrictDecimalFidelityTest {

    private JsonMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = JsonMapper.builder().build();
    }

    @Test
    void rejectsJsonNumberToken() {
        assertThatThrownBy(() -> mapper.readValue("{\"quantity\":0.75}", QuantityProbe.class))
                .isInstanceOf(DatabindException.class)
                .hasCauseInstanceOf(ContractTokenException.class)
                .satisfies(ex -> assertThat(((ContractTokenException) ex.getCause()).code())
                        .isEqualTo(ContractErrorCode.quantity_not_string));
    }

    @Test
    void rejectsExponentNotation() {
        assertThatThrownBy(() -> mapper.readValue("{\"quantity\":\"1.5e2\"}", QuantityProbe.class))
                .isInstanceOf(DatabindException.class)
                .hasCauseInstanceOf(ContractTokenException.class)
                .satisfies(ex -> assertThat(((ContractTokenException) ex.getCause()).code())
                        .isEqualTo(ContractErrorCode.quantity_not_string));
    }

    @Test
    void plainStringRoundTripPreservesTrailingZeros() throws Exception {
        QuantityProbe parsed = mapper.readValue("{\"quantity\":\"0.75000000\"}", QuantityProbe.class);
        assertThat(parsed.quantity()).isEqualByComparingTo(new BigDecimal("0.75000000"));
        assertThat(parsed.quantity().scale()).isEqualTo(8);

        String json = mapper.writeValueAsString(parsed);
        assertThat(json).isEqualTo("{\"quantity\":\"0.75000000\"}");
    }

    record QuantityProbe(
            @JsonDeserialize(using = StrictDecimalStringDeserializer.class)
            @JsonSerialize(using = ToPlainStringSerializer.class)
            BigDecimal quantity) {}
}
