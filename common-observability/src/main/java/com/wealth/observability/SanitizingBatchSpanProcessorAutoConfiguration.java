package com.wealth.observability;

import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.common.InternalTelemetryVersion;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessorBuilder;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.micrometer.tracing.autoconfigure.ConditionalOnEnabledTracingExport;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingProperties;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingProperties.Export;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Replaces Boot's default {@link BatchSpanProcessor} with one that privately wraps
 * the gRPC OTLP exporter in {@link SanitizingSpanExporter}. The sanitizer is not a
 * {@link SpanExporter} bean — registering it as one would double-export raw and
 * sanitized copies.
 */
@AutoConfiguration(after = OtlpTracingAutoConfiguration.class, before = OpenTelemetryTracingAutoConfiguration.class)
@ConditionalOnEnabledTracingExport("otlp")
@ConditionalOnBean(OtlpGrpcSpanExporter.class)
@EnableConfigurationProperties(OpenTelemetryTracingProperties.class)
public class SanitizingBatchSpanProcessorAutoConfiguration {

    private final OpenTelemetryTracingProperties openTelemetryTracingProperties;

    SanitizingBatchSpanProcessorAutoConfiguration(
            OpenTelemetryTracingProperties openTelemetryTracingProperties) {
        this.openTelemetryTracingProperties = openTelemetryTracingProperties;
    }

    @Bean
    BatchSpanProcessor otelSpanProcessor(
            OtlpGrpcSpanExporter otlpGrpcSpanExporter, ObjectProvider<MeterProvider> meterProvider) {
        Export properties = this.openTelemetryTracingProperties.getExport();
        SpanExporter sanitized = new SanitizingSpanExporter(otlpGrpcSpanExporter);
        BatchSpanProcessorBuilder builder = BatchSpanProcessor.builder(sanitized)
                .setExportUnsampledSpans(properties.isIncludeUnsampled())
                .setExporterTimeout(properties.getTimeout())
                .setMaxExportBatchSize(properties.getMaxBatchSize())
                .setMaxQueueSize(properties.getMaxQueueSize())
                .setScheduleDelay(properties.getScheduleDelay())
                .setInternalTelemetryVersion(InternalTelemetryVersion.LATEST);
        meterProvider.ifAvailable(builder::setMeterProvider);
        return builder.build();
    }
}
