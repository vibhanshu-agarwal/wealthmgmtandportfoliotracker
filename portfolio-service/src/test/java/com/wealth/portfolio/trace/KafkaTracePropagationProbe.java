package com.wealth.portfolio.trace;

import com.wealth.market.events.PriceUpdatedEvent;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Test-only consumer that captures the active Micrometer span at consume time (Property 10b listener
 * observation gate).
 *
 * <p>Asserting {@link Tracer#currentSpan()} verifies {@code spring.kafka.listener.observation-enabled}
 * fires when a {@code traceparent} control header is present. Trace-ID continuity vs the producer
 * span is a separate deferred gate (see migration spec task 11.2 partial note).
 */
@Component
public class KafkaTracePropagationProbe {

    public static final AtomicReference<String> CONSUMER_TRACE_ID = new AtomicReference<>();

    private final Tracer tracer;

    KafkaTracePropagationProbe(Tracer tracer) {
        this.tracer = tracer;
    }

    @KafkaListener(
            topics = "market-prices",
            groupId = "trace-propagation-probe",
            containerFactory = "priceUpdatedKafkaListenerContainerFactory")
    void probe(ConsumerRecord<String, PriceUpdatedEvent> record) {
        if (record.key() == null || !record.key().startsWith("TRC_")) {
            return;
        }
        Span current = tracer.currentSpan();
        if (current != null) {
            CONSUMER_TRACE_ID.compareAndSet(null, current.context().traceId());
        }
    }

    public static void reset() {
        CONSUMER_TRACE_ID.set(null);
    }
}
