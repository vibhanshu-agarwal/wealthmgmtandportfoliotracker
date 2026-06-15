package com.wealth.insight.trace;

import com.wealth.market.events.PriceUpdatedEvent;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Test-only consumer that captures the active Micrometer span at consume time for Property 10b.
 *
 * <p>Asserting {@link Tracer#currentSpan()} (not the raw {@code traceparent} header) verifies that
 * {@code spring.kafka.listener.observation-enabled} continues the producer trace.
 */
@Component
public class InsightKafkaTracePropagationProbe {

    public static final AtomicReference<String> CONSUMER_TRACE_ID = new AtomicReference<>();

    private final Tracer tracer;

    InsightKafkaTracePropagationProbe(Tracer tracer) {
        this.tracer = tracer;
    }

    @KafkaListener(
            topics = "market-prices",
            groupId = "insight-trace-propagation-probe",
            containerFactory = "kafkaListenerContainerFactory")
    void probe(ConsumerRecord<String, PriceUpdatedEvent> record) {
        if (!"TRACE".equals(record.key())) {
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
