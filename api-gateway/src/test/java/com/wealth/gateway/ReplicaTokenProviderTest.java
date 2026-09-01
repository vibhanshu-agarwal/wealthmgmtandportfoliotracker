package com.wealth.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ReplicaTokenProviderTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   \t\n", "\u2003"})
    void blankInputsExposeCanonicalEmptyToken(String rawName) {
        ReplicaTokenProvider provider = new ReplicaTokenProvider(rawName);

        assertThat(provider.replicaToken()).isEmpty();
    }

    @Test
    void fixedRawValueExposesDerivedTokenNotRawName() {
        String rawName = "api-gateway--0000000-abcdefg";
        ReplicaTokenProvider provider = new ReplicaTokenProvider(rawName);

        assertThat(provider.replicaToken())
                .isEqualTo("95ca17821ade")
                .isNotEqualTo(rawName);
    }

    @Test
    void repeatedAccessorCallsAreStable() {
        ReplicaTokenProvider provider = new ReplicaTokenProvider("api-gateway--0000000-abcdefg");

        assertThat(provider.replicaToken()).isEqualTo(provider.replicaToken());
    }
}
