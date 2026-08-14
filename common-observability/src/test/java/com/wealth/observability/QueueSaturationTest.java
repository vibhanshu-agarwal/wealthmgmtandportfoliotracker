package com.wealth.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 1 drop-not-block: 2,000 span ends against a stalled gRPC export must
 * finish in under 2 seconds and increment
 * {@code otel.sdk.processor.span.processed{error.type="queue_full"}}. Queue-full
 * drops happen inside {@link BatchSpanProcessor}; this test does not wrap
 * {@link SpanExporter} to count in vs out.
 */
class QueueSaturationTest {

    private static final int SPAN_COUNT = 2_000;
    private static final Duration SPAN_LOOP_BOUND = Duration.ofSeconds(2);
    private static final Duration SHORT_TIMEOUT = Duration.ofSeconds(1);
    private static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");
    private static final String PROCESSED_METRIC = "otel.sdk.processor.span.processed";
    private static final String QUEUE_FULL = "queue_full";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    OpenTelemetrySdkAutoConfiguration.class,
                    OtlpTracingAutoConfiguration.class,
                    SanitizingBatchSpanProcessorAutoConfiguration.class,
                    OpenTelemetryTracingAutoConfiguration.class))
            .withUserConfiguration(TestMeterProviderConfig.class)
            .withPropertyValues(
                    "management.tracing.export.enabled=true",
                    "management.tracing.export.otlp.enabled=true",
                    "management.opentelemetry.tracing.export.otlp.transport=grpc",
                    "management.opentelemetry.tracing.export.max-queue-size=8",
                    "management.opentelemetry.tracing.export.max-batch-size=4",
                    "management.opentelemetry.tracing.export.timeout=1s",
                    "management.opentelemetry.tracing.export.otlp.timeout=1s",
                    "management.tracing.sampling.probability=1.0");

    @Test
    void twoThousandSpanEndsStayBoundedAndRecordQueueFullWhenExportIsStalled() throws IOException {
        try (AcceptAndHangListener hang = AcceptAndHangListener.bind()) {
            contextRunner
                    .withPropertyValues(
                            "management.opentelemetry.tracing.export.otlp.endpoint=http://127.0.0.1:"
                                    + hang.port())
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertExportPathAndSanitizingProcessor(context);

                        SdkTracerProvider tracerProvider = context.getBean(SdkTracerProvider.class);
                        long startedNanos = System.nanoTime();
                        for (int i = 0; i < SPAN_COUNT; i++) {
                            Span span = tracerProvider.get("queue-saturation-test")
                                    .spanBuilder("saturated")
                                    .startSpan();
                            span.end();
                        }
                        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedNanos);

                        assertThat(elapsed)
                                .as("creating and ending %s spans must not block on the stalled gRPC exporter",
                                        SPAN_COUNT)
                                .isLessThan(SPAN_LOOP_BOUND);

                        InMemoryMetricReader reader = context.getBean(InMemoryMetricReader.class);
                        Collection<MetricData> metrics = reader.collectAllMetrics();
                        long queueFull = queueFullCount(metrics);
                        assertThat(queueFull)
                                .as("%s{error.type=%s} must increment when the stalled exporter fills the queue; metrics=%s",
                                        PROCESSED_METRIC, QUEUE_FULL, metrics)
                                .isPositive();
                    });
        }
    }

    private static void assertExportPathAndSanitizingProcessor(AssertableApplicationContext context) {
        assertThat(context).hasSingleBean(OtlpGrpcSpanExporter.class);
        assertThat(context).hasSingleBean(BatchSpanProcessor.class);
        assertThat(context.getBeansOfType(SpanExporter.class).values())
                .noneMatch(SanitizingSpanExporter.class::isInstance)
                .anyMatch(OtlpGrpcSpanExporter.class::isInstance);
        BatchSpanProcessor processor = context.getBean(BatchSpanProcessor.class);
        assertThat(processor.getSpanExporter()).isInstanceOf(SanitizingSpanExporter.class);
        assertThat(processor).extracting("worker.maxQueueSize").isEqualTo(8L);
        assertThat(processor).extracting("worker.maxExportBatchSize").isEqualTo(4);
    }

    private static long queueFullCount(Collection<MetricData> metrics) {
        long total = 0L;
        for (MetricData metric : metrics) {
            if (!PROCESSED_METRIC.equals(metric.getName())) {
                continue;
            }
            for (LongPointData point : metric.getLongSumData().getPoints()) {
                if (QUEUE_FULL.equals(point.getAttributes().get(ERROR_TYPE))) {
                    total += point.getValue();
                }
            }
        }
        return total;
    }

    @Configuration(proxyBeanMethods = false)
    static class TestMeterProviderConfig {

        @Bean
        InMemoryMetricReader inMemoryMetricReader() {
            return InMemoryMetricReader.create();
        }

        @Bean
        MeterProvider meterProvider(InMemoryMetricReader inMemoryMetricReader) {
            return SdkMeterProvider.builder().registerMetricReader(inMemoryMetricReader).build();
        }
    }

    /**
     * TCP listener that accepts connections and never speaks gRPC, so
     * {@code OtlpGrpcSpanExporter} hangs until timeout instead of failing with
     * connection refused.
     */
    private static final class AcceptAndHangListener implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final Thread acceptor;
        private final List<Socket> accepted = new CopyOnWriteArrayList<>();
        private volatile boolean running = true;

        static AcceptAndHangListener bind() throws IOException {
            ServerSocket serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            AcceptAndHangListener listener = new AcceptAndHangListener(serverSocket);
            listener.acceptor.start();
            return listener;
        }

        private AcceptAndHangListener(ServerSocket serverSocket) {
            this.serverSocket = serverSocket;
            this.acceptor = new Thread(this::acceptForever, "queue-saturation-hang-listener");
            this.acceptor.setDaemon(true);
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        private void acceptForever() {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    accepted.add(socket);
                } catch (IOException ex) {
                    if (running) {
                        break;
                    }
                }
            }
        }

        @Override
        public void close() {
            running = false;
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            for (Socket socket : accepted) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
            try {
                acceptor.join(SHORT_TIMEOUT.toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
