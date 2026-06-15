package com.wealth.insight.trace;

import com.wealth.market.events.PriceUpdatedEvent;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Test-only consumer that captures {@code traceparent} continuity for Property 10b. */
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
        Header traceparent = record.headers().lastHeader("traceparent");
        if (traceparent != null) {
            String value = new String(traceparent.value(), StandardCharsets.UTF_8);
            CONSUMER_TRACE_ID.compareAndSet(null, TraceparentTestSupport.traceId(value));
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
