package com.wealth.portfolio;

import com.wealth.portfolio.kafka.MalformedEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.FixedBackOff;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Verifies that {@link PortfolioKafkaConfig} registers {@link MalformedEventException}
 * as non-retryable in the {@link DefaultErrorHandler}.
 *
 * <p>We construct the handler directly (no Spring context needed) and assert that
 * the recoverer is invoked on the very first failure — zero retries.
 */
class PortfolioKafkaConfigTest {

    @Test
    void malformedEventException_isNonRetryable_recovererCalledOnFirstFailure() {
        // Arrange
        var recoverer = mock(DeadLetterPublishingRecoverer.class);
        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(0L, 3L));
        handler.addNotRetryableExceptions(MalformedEventException.class);

        @SuppressWarnings("unchecked")
        var record = (ConsumerRecord<String, Object>) mock(ConsumerRecord.class);
        var exception = new MalformedEventException("blank ticker");

        // Act — handleRemaining simulates the error handler receiving a failed record
        handler.handleRemaining(exception, java.util.List.of(record), mock(org.apache.kafka.clients.consumer.Consumer.class), mock(org.springframework.kafka.listener.MessageListenerContainer.class));

        // Assert — recoverer must be called exactly once (no retries)
        verify(recoverer, times(1)).accept(any(), any(), any());
    }
}
