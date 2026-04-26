package com.wealth.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production-profile JWT round-trip: AuthController issues HS256 and the AWS-profile
 * decoder accepts the same token on protected routes.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"prod", "aws"})
class AwsJwtDecoderIntegrationTest {

    private static final String DEMO_USER_ID = "00000000-0000-0000-0000-000000000e2e";

    @DynamicPropertySource
    static void authProperties(DynamicPropertyRegistry registry) {
        registry.add("auth.jwt.secret", () -> TestJwtFactory.TEST_SECRET);
        registry.add("app.auth.email", () -> "demo@example.com");
        registry.add("app.auth.password", () -> "demo-password");
        registry.add("app.auth.user-id", () -> DEMO_USER_ID);
        registry.add("app.auth.name", () -> "Demo User");
    }

    @LocalServerPort
    int port;

    WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Test
    void loginTokenIssuedUnderAwsProfileIsAcceptedByGateway() {
        LoginDtos.LoginResponse login = webTestClient.post()
                .uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "demo@example.com", "password", "demo-password"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(LoginDtos.LoginResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(login).isNotNull();
        assertThat(login.token()).isNotBlank();
        assertThat(login.userId()).isEqualTo(DEMO_USER_ID);

        webTestClient.get()
                .uri("/api/portfolio")
                .header("Authorization", "Bearer " + login.token())
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status).as("gateway-issued JWT should not be rejected")
                                .isNotEqualTo(401));
    }
}