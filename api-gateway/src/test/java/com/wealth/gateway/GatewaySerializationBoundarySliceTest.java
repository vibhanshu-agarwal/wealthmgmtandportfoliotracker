package com.wealth.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Jackson 3 serialization boundary slice test (Task 10.2 / Property 11).
 *
 * <p>Asserts the autoconfigured {@link JsonMapper} bean backs WebFlux request/response
 * serialization for {@link AuthController}.
 */
@WebFluxTest(controllers = AuthController.class)
@Import(JwtSigner.class)
@TestPropertySource(properties = {
        "app.auth.email=dev@localhost.local",
        "app.auth.password=password",
        "app.auth.user-id=user-001",
        "app.auth.name=Development User",
        "auth.jwt.secret=local-dev-secret-change-me-min-32-chars"
})
class GatewaySerializationBoundarySliceTest {

    @Autowired
    WebTestClient webTestClient;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    void autoconfiguredMapper_isJackson3JsonMapper() {
        assertThat(jsonMapper.getClass().getName()).startsWith("tools.jackson.");
        assertThat(jsonMapper.getClass().getName()).doesNotContain("com.fasterxml.jackson");
    }

    @Test
    void loginResponse_serializesViaAutoconfiguredMapper() {
        webTestClient.post()
                .uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"email":"dev@localhost.local","password":"password"}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.token").isNotEmpty()
                .jsonPath("$.userId").isEqualTo("user-001")
                .jsonPath("$.email").isEqualTo("dev@localhost.local")
                .jsonPath("$.name").isEqualTo("Development User");
    }
}
