package com.wealth.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributeType;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.ExceptionEventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * SpanExporter that substitutes a sanitized {@link DelegatingSpanData} view before
 * delegating. Not a Spring bean — task 6.2 wraps this privately in a processor.
 */
public final class SanitizingSpanExporter implements SpanExporter {

    private static final String REDACTED = "[REDACTED]";
    private static final String EXCEPTION_EVENT_NAME = "exception";
    private static final AttributeKey<String> EXCEPTION_TYPE = AttributeKey.stringKey("exception.type");
    private static final AttributeKey<String> EXCEPTION_MESSAGE = AttributeKey.stringKey("exception.message");

    private final SpanExporter delegate;

    public SanitizingSpanExporter(SpanExporter delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        List<SpanData> sanitized = new ArrayList<>(spans.size());
        for (SpanData span : spans) {
            sanitized.add(new SanitizedSpanData(span));
        }
        return delegate.export(sanitized);
    }

    @Override
    public CompletableResultCode flush() {
        return delegate.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        return delegate.shutdown();
    }

    private static final class SanitizedSpanData extends DelegatingSpanData {

        private final Attributes attributes;
        private final List<EventData> events;
        private final StatusData status;

        SanitizedSpanData(SpanData delegate) {
            super(delegate);
            this.events = sanitizeEvents(delegate.getEvents());
            this.attributes = sanitizeSpanAttributes(delegate.getAttributes());
            this.status = sanitizeStatus(delegate.getStatus());
        }

        @Override
        public Attributes getAttributes() {
            return attributes;
        }

        @Override
        public List<EventData> getEvents() {
            return events;
        }

        @Override
        public StatusData getStatus() {
            return status;
        }
    }

    private static Attributes sanitizeSpanAttributes(Attributes attributes) {
        AttributesBuilder builder = Attributes.builder();
        attributes.forEach((key, value) -> {
            if (!AttributeDenySet.isDenied(key.getKey())) {
                copyAttribute(builder, key, value);
            }
        });
        return builder.build();
    }

    private static List<EventData> sanitizeEvents(List<EventData> events) {
        List<EventData> sanitized = new ArrayList<>(events.size());
        for (EventData event : events) {
            sanitized.add(sanitizeEvent(event));
        }
        return List.copyOf(sanitized);
    }

    private static EventData sanitizeEvent(EventData event) {
        if (event instanceof ExceptionEventData exceptionEvent) {
            return EventData.create(
                    event.getEpochNanos(),
                    EXCEPTION_EVENT_NAME,
                    Attributes.of(
                            EXCEPTION_TYPE, exceptionType(exceptionEvent),
                            EXCEPTION_MESSAGE, REDACTED));
        }
        Attributes sanitizedAttributes = sanitizeEventAttributes(event.getAttributes());
        if (sanitizedAttributes.equals(event.getAttributes())) {
            return event;
        }
        return EventData.create(
                event.getEpochNanos(),
                event.getName(),
                sanitizedAttributes,
                event.getTotalAttributeCount());
    }

    private static String exceptionType(ExceptionEventData event) {
        String type = event.getAttributes().get(EXCEPTION_TYPE);
        if (type != null && !type.isEmpty()) {
            return type;
        }
        return event.getException().getClass().getName();
    }

    private static Attributes sanitizeEventAttributes(Attributes attributes) {
        AttributesBuilder builder = Attributes.builder();
        attributes.forEach((key, value) -> {
            String name = key.getKey();
            if (AttributeDenySet.isDenied(name)) {
                return;
            }
            if (isSensitiveExceptionKey(name)) {
                if (key.getType() == AttributeType.STRING) {
                    builder.put(AttributeKey.stringKey(name), REDACTED);
                }
                return;
            }
            copyAttribute(builder, key, value);
        });
        return builder.build();
    }

    private static boolean isSensitiveExceptionKey(String name) {
        return name.startsWith("exception.") && !EXCEPTION_TYPE.getKey().equals(name);
    }

    private static StatusData sanitizeStatus(StatusData status) {
        String description = status.getDescription();
        if (description == null || description.isEmpty()) {
            return status;
        }
        return StatusData.create(status.getStatusCode(), REDACTED);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void copyAttribute(AttributesBuilder builder, AttributeKey<?> key, Object value) {
        builder.put((AttributeKey) key, value);
    }
}
