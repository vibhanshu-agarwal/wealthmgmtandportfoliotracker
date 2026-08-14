package com.wealth.gateway.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import com.wealth.ApiGatewayApplication;
import com.wealth.gateway.TestJwtFactory;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * Requirement 6.4: context close (SIGTERM → Spring shutdown hook → {@code doClose()}) must
 * export spans that are still queued in {@code BatchSpanProcessor}.
 *
 * <p>Boots the full api-gateway WebFlux stack so Netty graceful drain runs before
 * {@code SdkTracerProvider} destroy. Production YAML is not changed
 * ({@code server.shutdown=graceful} is test-only).
 */
@Tag("integration")
class SigtermSpanFlushIT {

    private static final Duration DRAIN_DELAY = Duration.ofSeconds(2);
    private static final Duration ACA_TERMINATION_GRACE = Duration.ofSeconds(30);

    @Test
    void contextClose_exportsPendingSpanAfterWebFluxDrain_underAcaGrace() throws Exception {
        CaptureConfig.drainStarted = new CountDownLatch(1);
        CaptureConfig.exporter = new RecordingSpanExporter();

        ConfigurableApplicationContext context =
                new SpringApplicationBuilder(ApiGatewayApplication.class, CaptureConfig.class)
                        .run(
                                "--spring.profiles.active=aws",
                                "--server.port=0",
                                "--server.shutdown=graceful",
                                "--spring.lifecycle.timeout-per-shutdown-phase=15s",
                                "--auth.jwt.secret=" + TestJwtFactory.TEST_SECRET,
                                "--management.health.redis.enabled=false",
                                "--management.tracing.export.enabled=false",
                                "--management.otlp.metrics.export.enabled=false",
                                "--management.tracing.sampling.probability=1.0",
                                "--management.opentelemetry.tracing.export.schedule-delay=60s");

        try {
            int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
            var inFlight =
                    HttpClient.newHttpClient()
                            .sendAsync(
                                    HttpRequest.newBuilder(
                                                    URI.create(
                                                            "http://127.0.0.1:"
                                                                    + port
                                                                    + "/api/internal/shutdown-drain-probe"))
                                            .GET()
                                            .timeout(Duration.ofSeconds(20))
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString());
            assertThat(CaptureConfig.drainStarted.await(10, TimeUnit.SECONDS))
                    .as("in-flight WebFlux drain request must have reached the delayed handler")
                    .isTrue();

            String spanName = "sigterm-flush-" + UUID.randomUUID();
            context.getBean(Tracer.class).spanBuilder(spanName).startSpan().end();

            assertThat(CaptureConfig.exporter.spanNames())
                    .as("60s schedule-delay must leave the ended span queued until context close")
                    .doesNotContain(spanName);

            long startedNanos = System.nanoTime();
            context.close();
            Duration closeElapsed = Duration.ofNanos(System.nanoTime() - startedNanos);

            assertThat(CaptureConfig.exporter.spanNames())
                    .as("SdkTracerProvider.close() must export the pending span")
                    .contains(spanName);
            assertThat(closeElapsed)
                    .as("close() must wait for the in-flight WebFlux drain, then finish inside ACA grace")
                    .isGreaterThan(Duration.ofSeconds(1))
                    .isLessThan(ACA_TERMINATION_GRACE);
            assertThat(inFlight)
                    .as("graceful drain must complete the delayed request rather than abort it")
                    .succeedsWithin(Duration.ofSeconds(5))
                    .returns(200, HttpResponse::statusCode);
        } finally {
            if (context.isActive()) {
                context.close();
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CaptureConfig {

        static volatile CountDownLatch drainStarted = new CountDownLatch(1);
        static volatile RecordingSpanExporter exporter = new RecordingSpanExporter();

        @Bean
        SpanExporter capturingSpanExporter() {
            return exporter;
        }

        @Bean
        RouterFunction<ServerResponse> shutdownDrainProbe() {
            return route(
                    GET("/api/internal/shutdown-drain-probe"),
                    request -> {
                        drainStarted.countDown();
                        return Mono.delay(DRAIN_DELAY).then(ServerResponse.ok().bodyValue("drained"));
                    });
        }
    }

    /**
     * Test-only recorder. {@code InMemorySpanExporter.shutdown()} clears its buffer, which would
     * hide spans flushed during context close.
     */
    static final class RecordingSpanExporter implements SpanExporter {

        private final List<SpanData> exported = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            exported.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }

        List<String> spanNames() {
            return exported.stream().map(SpanData::getName).toList();
        }
    }
}
