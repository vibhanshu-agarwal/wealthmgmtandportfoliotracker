package com.wealth.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.tracing.autoconfigure.TracingProperties;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingProperties;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingProperties;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.Transport;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 1 startup/request isolation: export enabled against a dead local gRPC
 * port must not fail context start or block span end. Does not use
 * {@code forceFlush()}/{@code shutdown()} as the request — those may wait on the
 * exporter. Queue saturation is 11.2.
 */
class UnreachableExporterTest {

    private static final Duration REQUEST_WALL_CLOCK_BOUND = Duration.ofMillis(200);
    private static final Duration SHORT_TIMEOUT = Duration.ofSeconds(1);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    OpenTelemetrySdkAutoConfiguration.class,
                    OtlpTracingAutoConfiguration.class,
                    SanitizingBatchSpanProcessorAutoConfiguration.class,
                    OpenTelemetryTracingAutoConfiguration.class))
            .withPropertyValues(
                    "management.tracing.export.enabled=true",
                    "management.tracing.export.otlp.enabled=true",
                    "management.opentelemetry.tracing.export.otlp.transport=grpc",
                    "management.opentelemetry.tracing.export.otlp.endpoint=http://127.0.0.1:1",
                    "management.opentelemetry.tracing.export.otlp.connect-timeout=1s",
                    "management.opentelemetry.tracing.export.timeout=1s",
                    "management.tracing.sampling.probability=1.0");

    @Test
    void contextStartsAndSpanEndDoesNotBlockWhenOtlpEndpointIsUnreachable() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertConfiguredPropertiesBound(context);
            assertExportPathAndSanitizingProcessor(context);

            SdkTracerProvider tracerProvider = context.getBean(SdkTracerProvider.class);
            long startedNanos = System.nanoTime();
            Span span = tracerProvider.get("unreachable-exporter-test").spanBuilder("request").startSpan();
            span.end();
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedNanos);

            assertThat(elapsed)
                    .as("span start+end must not block on the dead gRPC exporter (connect-timeout=%s)",
                            SHORT_TIMEOUT)
                    .isLessThan(REQUEST_WALL_CLOCK_BOUND);
        });
    }

    private static void assertConfiguredPropertiesBound(AssertableApplicationContext context) {
        assertThat(context.getEnvironment().getProperty("management.tracing.export.enabled")).isEqualTo("true");
        assertThat(context.getEnvironment().getProperty("management.tracing.export.otlp.enabled")).isEqualTo("true");

        TracingProperties sampling = context.getBean(TracingProperties.class);
        assertThat(sampling.getSampling().getProbability()).isEqualTo(1.0f);

        OtlpTracingProperties otlp = context.getBean(OtlpTracingProperties.class);
        assertThat(otlp.getEndpoint()).isEqualTo("http://127.0.0.1:1");
        assertThat(otlp.getTransport()).isEqualTo(Transport.GRPC);
        assertThat(otlp.getConnectTimeout()).isEqualTo(SHORT_TIMEOUT);

        OpenTelemetryTracingProperties tracing = context.getBean(OpenTelemetryTracingProperties.class);
        assertThat(tracing.getExport().getTimeout()).isEqualTo(SHORT_TIMEOUT);
    }

    private static void assertExportPathAndSanitizingProcessor(AssertableApplicationContext context) {
        assertThat(context).hasSingleBean(OtlpGrpcSpanExporter.class);
        assertThat(context).hasSingleBean(BatchSpanProcessor.class);
        assertThat(context.getBeansOfType(SpanExporter.class).values())
                .noneMatch(SanitizingSpanExporter.class::isInstance)
                .anyMatch(OtlpGrpcSpanExporter.class::isInstance);
        BatchSpanProcessor processor = context.getBean(BatchSpanProcessor.class);
        assertThat(processor.getSpanExporter()).isInstanceOf(SanitizingSpanExporter.class);
    }
}
