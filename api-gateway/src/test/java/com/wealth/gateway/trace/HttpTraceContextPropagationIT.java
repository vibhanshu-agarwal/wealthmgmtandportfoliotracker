package com.wealth.gateway.trace;

import static com.wealth.gateway.trace.TraceparentTestSupport.sampleTraceparent;
import static com.wealth.gateway.trace.TraceparentTestSupport.traceId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;
import com.wealth.gateway.TestContainerImages;
import com.wealth.gateway.TestJwtFactory;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Property 10a: HTTP trace-context propagation — {@code api-gateway → insight-service}.
 *
 * <p>Uses an in-process HTTP stub for the insight-service route and asserts the gateway forwards
 * the W3C {@code traceparent} header with an unbroken trace id across the reactive proxy boundary.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestPropertySource(
        properties = {
            "management.health.redis.enabled=false",
            "management.tracing.export.enabled=false",
            "management.otlp.metrics.export.enabled=false"
        })
class HttpTraceContextPropagationIT {

    private static final int REDIS_PORT = 6379;

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>(TestContainerImages.REDIS).withExposedPorts(REDIS_PORT);

    private static final List<String> capturedTraceparents = new CopyOnWriteArrayList<>();

    private static HttpServer insightStub;
    private static int insightStubPort;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) throws IOException {
        startInsightStub();
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(REDIS_PORT));
        registry.add("auth.jwt.secret", () -> TestJwtFactory.TEST_SECRET);
        registry.add("app.routes.insight-url", () -> "http://127.0.0.1:" + insightStubPort);
    }

  private static void startInsightStub() throws IOException {
        if (insightStub != null) {
            return;
        }
        insightStub = HttpServer.create(new InetSocketAddress(0), 0);
        insightStubPort = insightStub.getAddress().getPort();
        insightStub.createContext(
                "/api/insights/trace-probe",
                exchange -> {
                    String traceparent = exchange.getRequestHeaders().getFirst("traceparent");
                    if (traceparent != null) {
                        capturedTraceparents.add(traceparent);
                    }
                    byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        insightStub.start();
    }

    @LocalServerPort
    int port;

    WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        capturedTraceparents.clear();
        webTestClient =
                WebTestClient.bindToServer()
                        .baseUrl("http://localhost:" + port)
                        .responseTimeout(Duration.ofSeconds(10))
                        .build();
    }

    @AfterEach
    void tearDown() {
        capturedTraceparents.clear();
    }

    @AfterAll
    static void stopInsightStub() {
        if (insightStub != null) {
            insightStub.stop(0);
            insightStub = null;
        }
    }

    @Test
    void gateway_forwardsW3cTraceparentToInsightRoute() {
        String inbound = sampleTraceparent();
        String expectedTraceId = traceId(inbound);

        webTestClient
                .get()
                .uri("/api/insights/trace-probe")
                .header("Authorization", "Bearer " + TestJwtFactory.validSeedUserToken())
                .header("traceparent", inbound)
                .exchange()
                .expectStatus()
                .isOk();

        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(
                        () -> {
                            assertThat(capturedTraceparents).isNotEmpty();
                            assertThat(traceId(capturedTraceparents.getFirst())).isEqualTo(expectedTraceId);
                        });
    }
}
