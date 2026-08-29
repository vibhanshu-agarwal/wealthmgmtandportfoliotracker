package com.wealth.gateway.presence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DemoPresenceServiceHashTest {

    @Test
    void hashSessionKey_isDeterministicLowerCaseHex() {
        String first = DemoPresenceService.hashSessionKey("session-alpha");
        String second = DemoPresenceService.hashSessionKey("session-alpha");

        assertThat(first).isEqualTo(second);
        assertThat(first).matches("[0-9a-f]{64}");
        assertThat(first).doesNotContain("session-alpha");
    }

    @Test
    void hashSessionKey_differsForDifferentInputs() {
        assertThat(DemoPresenceService.hashSessionKey("one"))
                .isNotEqualTo(DemoPresenceService.hashSessionKey("two"));
    }
}
