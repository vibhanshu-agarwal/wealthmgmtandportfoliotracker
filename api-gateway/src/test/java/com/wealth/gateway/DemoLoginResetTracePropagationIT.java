package com.wealth.gateway;

import com.sun.net.httpserver.HttpServer;
import com.wealth.gateway.auth.AuthenticationService;
import com.wealth.gateway.auth.LoginResponse;
import com.wealth.gateway.auth.SignupService;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Catches lost observation context, construction-time port capture, and bypassed route targets. */
@Tag("integration")
@SpringBootTest(classes = com.wealth.ApiGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.profiles.active=local", "spring.main.web-application-type=reactive",
                "management.health.redis.enabled=false", "management.tracing.export.enabled=false",
                "management.otlp.metrics.export.enabled=false"})
@AutoConfigureTracing(export = false)
@Import(DemoLoginResetTracePropagationIT.Tracing.class)
@org.testcontainers.junit.jupiter.Testcontainers
class DemoLoginResetTracePropagationIT {
    @org.testcontainers.junit.jupiter.Container
    static final org.testcontainers.containers.GenericContainer<?> redis = new org.testcontainers.containers.GenericContainer<>(TestContainerImages.REDIS)
            .withExposedPorts(6379);
    static final List<Request> requests = new CopyOnWriteArrayList<>();
    static HttpServer portfolio;
    @LocalServerPort int port;
    @org.springframework.test.context.bean.override.mockito.MockitoBean AuthenticationService authentication;
    @org.springframework.test.context.bean.override.mockito.MockitoBean SignupService signup;
    @org.springframework.test.context.bean.override.mockito.MockitoBean InternalApiKeyProvider key;
    @org.springframework.test.context.bean.override.mockito.MockitoBean CloudFrontOriginSecretProvider origin;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        portfolio = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        portfolio.createContext("/", exchange -> {
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("traceparent"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] body = (exchange.getRequestMethod().equals("GET")
                    ? "[{\"id\":\"00000000-0000-0000-0000-000000000002\",\"userId\":\"00000000-0000-0000-0000-0000000d3110\",\"version\":71,\"updatedAt\":\"2020-01-01T00:00:00Z\"}]"
                    : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        portfolio.start();
        registry.add("app.routes.portfolio-url", () -> "http://127.0.0.1:" + portfolio.getAddress().getPort());
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("auth.jwt.secret", () -> TestJwtFactory.TEST_SECRET);
    }

    @AfterAll static void stop() { if (portfolio != null) portfolio.stop(0); }

    @Test void bothSelfCallsCarryTheInboundTraceThroughLoopbackAndProductionRoutes() {
        String trace = UUID.randomUUID().toString().replace("-", "");
        String jwt = TestJwtFactory.demoUserToken(UUID.randomUUID().toString());
        when(authentication.authenticate(any())).thenReturn(Mono.just(new LoginResponse(jwt,
                DemoLoginResetClient.DEMO_USER_ID, "demo@example.com", "Demo")));
        when(key.isConfigured()).thenReturn(true);
        when(key.value()).thenReturn("wave8-test-key");
        when(origin.isRequired()).thenReturn(false);
        requests.clear();
        WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(10)).build()
                .post().uri("/api/auth/login").header("traceparent", "00-" + trace + "-1234567890abcdef-01")
                .bodyValue(new LoginDtos.LoginRequest("demo@example.com", "password"))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.token").isEqualTo(jwt);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).method()).isEqualTo("GET");
        assertThat(requests.get(0).path()).isEqualTo("/api/portfolio");
        assertThat(requests.get(1).method()).isEqualTo("POST");
        assertThat(requests.get(1).path()).isEqualTo("/api/internal/portfolio/demo-reset");
        assertThat(requests.get(1).body()).isEqualTo("{\"expectedVersion\":71}");
        for (Request request : requests) assertThat(request.traceparent()).matches("00-" + trace + "-[0-9a-f]{16}-01");
    }

    record Request(String method, String path, String traceparent, String body) { }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class Tracing {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC); }
        @Bean io.opentelemetry.context.propagation.TextMapPropagator w3c() {
            return io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance();
        }
    }
}
