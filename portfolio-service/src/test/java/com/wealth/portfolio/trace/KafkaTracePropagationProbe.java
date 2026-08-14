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
 * observation gate, and Property 2 consumer-half continuity).
 *
 * <p>Asserting {@link Tracer#currentSpan()} verifies {@code spring.kafka.listener.observation-enabled}
 * fires when a {@code traceparent} control header is present. Trace and span IDs are captured so the
 * Consumer_Wire_Test can assert continuity of the injected header.
 */
@Component
public class KafkaTracePropagationProbe {

    public static final AtomicReference<String> CONSUMER_TRACE_ID = new AtomicReference<>();
    public static final AtomicReference<String> CONSUMER_SPAN_ID = new AtomicReference<>();

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
            CONSUMER_SPAN_ID.compareAndSet(null, current.context().spanId());
        }
    }

    public static void reset() {
        CONSUMER_TRACE_ID.set(null);
        CONSUMER_SPAN_ID.set(null);
    }
}
