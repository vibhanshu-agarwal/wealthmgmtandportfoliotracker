package com.wealth.insight.advisor;

import org.junit.jupiter.api.RepeatedTest;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link AdvisorUnavailableException}.
 * Verifies message and cause preservation for random inputs.
 */
class AdvisorUnavailableExceptionPropertyTest {

    /**
     * Property: For any random message and cause, the exception preserves both.
     */
    @RepeatedTest(100)
    void messageAndCause_always_preserved() {
        String message = "error-" + UUID.randomUUID() + "-" + ThreadLocalRandom.current().nextInt();
        Throwable cause = new RuntimeException("cause-" + UUID.randomUUID());

        AdvisorUnavailableException ex = new AdvisorUnavailableException(message, cause);

        assertThat(ex.getMessage()).isEqualTo(message);
        assertThat(ex.getCause()).isSameAs(cause);
    }

    /**
     * Property: For any random message, the message-only constructor preserves it
     * and cause is null.
     */
    @RepeatedTest(100)
    void messageOnly_always_preserved_causeIsNull() {
        String message = "error-" + UUID.randomUUID() + "-" + ThreadLocalRandom.current().nextInt();

        AdvisorUnavailableException ex = new AdvisorUnavailableException(message);

        assertThat(ex.getMessage()).isEqualTo(message);
        assertThat(ex.getCause()).isNull();
    }
}
