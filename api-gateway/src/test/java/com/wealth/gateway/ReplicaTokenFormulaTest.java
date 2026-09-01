package com.wealth.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ReplicaTokenFormulaTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   \t\n", "\u2003"})
    void nullBlankAndWhitespaceReturnEmptyToken(String rawName) {
        assertThat(ReplicaTokenFormula.compute(rawName)).isEmpty();
    }

    @Test
    void fixedVectorProducesExpectedLowercaseHexToken() {
        assertThat(ReplicaTokenFormula.compute("api-gateway--0000000-abcdefg"))
                .isEqualTo("95ca17821ade")
                .matches("[0-9a-f]{12}");
    }

    @Test
    void computeIsDeterministic() {
        String rawName = "api-gateway--0000000-abcdefg";

        assertThat(ReplicaTokenFormula.compute(rawName))
                .isEqualTo(ReplicaTokenFormula.compute(rawName));
    }

    @Test
    void preservesLeadingAndTrailingWhitespaceWithoutTrimming() {
        String withWhitespace = " api-gateway--0000000-abcdefg ";

        assertThat(ReplicaTokenFormula.compute(withWhitespace))
                .isNotEqualTo(ReplicaTokenFormula.compute("api-gateway--0000000-abcdefg"))
                .matches("[0-9a-f]{12}");
    }
}
