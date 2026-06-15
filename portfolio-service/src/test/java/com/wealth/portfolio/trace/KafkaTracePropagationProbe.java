package com.wealth.portfolio.trace;

import com.wealth.market.events.PriceUpdatedEvent;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Test-only consumer that captures {@code traceparent} continuity for Property 10b. */
@Component
public class KafkaTracePropagationProbe {

    public static final AtomicReference<String> CONSUMER_TRACE_ID = new AtomicReference<>();

    private static final Pattern TRACEPARENT =
            Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");

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
        Header traceparent = record.headers().lastHeader("traceparent");
        if (traceparent != null) {
            CONSUMER_TRACE_ID.compareAndSet(null, traceId(new String(traceparent.value(), StandardCharsets.UTF_8)));
        }
        Span current = tracer.currentSpan();
        if (current != null) {
            CONSUMER_TRACE_ID.compareAndSet(null, current.context().traceId());
        }
    }

    public static void reset() {
        CONSUMER_TRACE_ID.set(null);
    }

    private static String traceId(String traceparent) {
        var matcher = TRACEPARENT.matcher(traceparent);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid traceparent: " + traceparent);
        }
        return matcher.group(1);
    }
}
