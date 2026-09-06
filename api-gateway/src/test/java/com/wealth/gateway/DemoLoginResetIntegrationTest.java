package com.wealth.gateway;

import com.wealth.gateway.auth.AuthenticationService;
import com.wealth.gateway.auth.InvalidCredentialsException;
import com.wealth.gateway.auth.LoginResponse;
import com.wealth.gateway.auth.SignupService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DemoLoginResetIntegrationTest {
    @Test
    void eligibleLoginSendsExactlyTheObservedVersionOnTheWireWithoutASecondRead() throws Exception {
        List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<String> bodies = new java.util.concurrent.CopyOnWriteArrayList<>();
        var stub = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("localhost", 0), 0);
        stub.createContext("/", exchange -> {
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            byte[] body = (exchange.getRequestMethod().equals("GET")
                    ? DemoLoginResetOrchestratorTest.PORTFOLIO : "{}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        stub.start();
        try {
            var properties = new DemoLoginResetProperties(Duration.ofMinutes(30), Duration.ofSeconds(2),
                    Duration.ofSeconds(2), Duration.ofSeconds(4));
            var key = new InternalApiKeyProvider("key");
            var origin = new CloudFrontOriginSecretProvider("");
            var transport = new DemoLoginResetClient(WebClient.builder(),
                    new GatewayLoopbackTargetProvider(new MockEnvironment().withProperty("local.server.port",
                            Integer.toString(stub.getAddress().getPort()))), key, origin, properties,
                    Clock.fixed(Instant.parse("2026-09-06T00:31:00Z"), ZoneOffset.UTC), new ObjectMapper());
            var orchestrator = new DemoLoginResetOrchestrator(transport, properties, key, origin,
                    new DemoLoginResetDiagnostics(new ReplicaTokenProvider("")), System::nanoTime, r -> Mono.empty());
            reactor.test.StepVerifier.create(orchestrator.afterLogin(new LoginResponse("jwt",
                    DemoLoginResetClient.DEMO_USER_ID, "demo", "Demo"))).verifyComplete();
            assertThat(calls).containsExactly("GET /api/portfolio", "POST /api/internal/portfolio/demo-reset");
            assertThat(new ObjectMapper().readTree(bodies.getLast()).path("expectedVersion").longValue()).isEqualTo(71L);
        } finally { stub.stop(0); }
    }

    @Test
    void successfulDemoAuthenticationCallsEligibilityOnceWithTheFreshJwtAndPreservesLogin() {
        List<ClientRequest> requests = new ArrayList<>();
        var properties = new DemoLoginResetProperties(Duration.ofMinutes(30), Duration.ofSeconds(2),
                Duration.ofSeconds(2), Duration.ofSeconds(4));
        var transport = new DemoLoginResetClient(WebClient.builder().exchangeFunction(request -> {
            requests.add(request);
            return Mono.just(ClientResponse.create(org.springframework.http.HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body("[]").build());
        }), new GatewayLoopbackTargetProvider(new MockEnvironment().withProperty("local.server.port", "18081")),
                new InternalApiKeyProvider("key"), new CloudFrontOriginSecretProvider(""), properties,
                Clock.fixed(Instant.parse("2026-09-06T00:31:00Z"), ZoneOffset.UTC), new ObjectMapper());
        AuthenticationService authentication = mock(AuthenticationService.class);
        when(authentication.authenticate(any())).thenReturn(Mono.just(new LoginResponse("fresh-jwt",
                DemoLoginResetClient.DEMO_USER_ID, "demo@example.com", "Demo")));
        var orchestrator = new DemoLoginResetOrchestrator(transport, properties,
                new InternalApiKeyProvider("key"), new CloudFrontOriginSecretProvider(""),
                new DemoLoginResetDiagnostics(new ReplicaTokenProvider("")), System::nanoTime, r -> Mono.empty());
        var controller = new AuthController(authentication, mock(SignupService.class), orchestrator);
        WebTestClient.bindToController(controller).build().post().uri("/api/auth/login")
                .bodyValue(new LoginDtos.LoginRequest("demo@example.com", "password")).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.token").isEqualTo("fresh-jwt")
                .jsonPath("$.userId").isEqualTo(DemoLoginResetClient.DEMO_USER_ID)
                .jsonPath("$.email").isEqualTo("demo@example.com").jsonPath("$.name").isEqualTo("Demo");
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.method().name()).isEqualTo("GET");
            assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer fresh-jwt");
        });
    }
}
