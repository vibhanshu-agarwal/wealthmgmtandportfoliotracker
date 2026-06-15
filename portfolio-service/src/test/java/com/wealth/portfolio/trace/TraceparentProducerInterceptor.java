package com.wealth.portfolio.trace;

import com.wealth.market.events.PriceUpdatedEvent;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.internals.RecordHeader;

/** Test-only producer hook that stamps W3C {@code traceparent} on outbound records. */
public class TraceparentProducerInterceptor implements ProducerInterceptor<String, PriceUpdatedEvent> {

    private static final ThreadLocal<String> TRACEPARENT = new ThreadLocal<>();

    public static void seedTraceparent(String traceparent) {
        TRACEPARENT.set(traceparent);
    }

    public static void clearTraceparent() {
        TRACEPARENT.remove();
    }

    @Override
    public ProducerRecord<String, PriceUpdatedEvent> onSend(ProducerRecord<String, PriceUpdatedEvent> record) {
        String traceparent = TRACEPARENT.get();
        if (traceparent != null && record.headers().lastHeader("traceparent") == null) {
            record.headers().add(new RecordHeader("traceparent", traceparent.getBytes(StandardCharsets.UTF_8)));
        }
        return record;
    }

    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {}

    @Override
    public void close() {}

    @Override
    public void configure(Map<String, ?> configs) {}
}
