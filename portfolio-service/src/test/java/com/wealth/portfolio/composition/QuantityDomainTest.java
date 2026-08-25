package com.wealth.portfolio.composition;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class QuantityDomainTest {

    @ParameterizedTest
    @MethodSource("inDomain")
    void acceptsQuantitiesInsideDomain(String raw) {
        assertThat(QuantityDomain.isValid(new BigDecimal(raw))).isTrue();
    }

    @ParameterizedTest
    @MethodSource("outOfDomain")
    void rejectsQuantitiesOutsideDomain(String raw) {
        assertThat(QuantityDomain.isValid(new BigDecimal(raw))).isFalse();
    }

    @Test
    void rejectsNull() {
        assertThat(QuantityDomain.isValid(null)).isFalse();
    }

    @Test
    void maximumBoundaryIsInclusive() {
        assertThat(QuantityDomain.isValid(new BigDecimal("99999999999.99999999"))).isTrue();
        assertThat(QuantityDomain.isValid(new BigDecimal("100000000000"))).isFalse();
        assertThat(QuantityDomain.isValid(new BigDecimal("99999999999.999999990"))).isFalse();
    }

    static Stream<Arguments> inDomain() {
        return Stream.of(
                Arguments.of("0.00000001"),
                Arguments.of("1"),
                Arguments.of("0.75"),
                Arguments.of("0.75000000"),
                Arguments.of("99999999999.99999999"),
                Arguments.of("12345678901.12345678"));
    }

    static Stream<Arguments> outOfDomain() {
        return Stream.of(
                Arguments.of("0"),
                Arguments.of("-1"),
                Arguments.of("-0.00000001"),
                Arguments.of("0.123456789"),
                Arguments.of("100000000000"),
                Arguments.of("99999999999.999999991"),
                Arguments.of("123456789012"));
    }
}
