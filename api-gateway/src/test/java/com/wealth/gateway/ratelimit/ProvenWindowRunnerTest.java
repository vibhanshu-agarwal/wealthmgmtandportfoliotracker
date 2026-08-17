package com.wealth.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProvenWindowRunnerTest {

    @Test
    void discardsCrossedWindowIssuesFreshKeyAndReturnsSecondAttempt() throws Exception {
        Iterator<String> seconds = List.of("100", "101", "100", "100").iterator();
        List<String> issuedKeys = new ArrayList<>();
        List<String> keysSeenByBurst = new ArrayList<>();
        RawAttempt attempt1 = new RawAttempt(List.of(), null, 1L);
        RawAttempt attempt2 = new RawAttempt(List.of(), null, 2L);
        Iterator<RawAttempt> attempts = List.of(attempt1, attempt2).iterator();

        ProvenWindowRunner runner =
                new ProvenWindowRunner(
                        seconds::next,
                        () -> {
                            String key = "key-" + issuedKeys.size();
                            issuedKeys.add(key);
                            return key;
                        },
                        key -> {
                            keysSeenByBurst.add(key);
                            return attempts.next();
                        });

        RawAttempt proven = runner.run(30, Duration.ofSeconds(10));

        assertThat(issuedKeys).hasSize(2);
        assertThat(keysSeenByBurst).containsExactly(issuedKeys.get(0), issuedKeys.get(1));
        assertThat(issuedKeys.get(0)).isNotEqualTo(issuedKeys.get(1));
        assertThat(proven).isSameAs(attempt2);
    }
}
