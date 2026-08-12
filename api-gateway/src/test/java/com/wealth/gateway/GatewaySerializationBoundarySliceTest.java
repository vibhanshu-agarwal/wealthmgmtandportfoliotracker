package com.wealth.gateway;

import com.wealth.gateway.auth.AuthenticationService;
import com.wealth.gateway.auth.LoginResponse;
import com.wealth.gateway.auth.SignupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Jackson 3 serialization boundary slice test (Task 10.2 / Property 11).
 *
 * <p>Asserts the autoconfigured {@link JsonMapper} bean backs WebFlux request/response
 * serialization for {@link AuthController}.
 */
@WebFluxTest(controllers = AuthController.class)
class GatewaySerializationBoundarySliceTest {

    @Autowired
    WebTestClient webTestClient;

    @Autowired
    JsonMapper jsonMapper;

    // AuthController now constructor-injects AuthenticationService/SignupService (Task 5,
    // new-user-signup-profile) instead of @Value-injected demo credentials; @WebFluxTest only
    // loads the controller layer, so these datasource-backed beans are mocked to satisfy wiring.
    @MockitoBean
    AuthenticationService authenticationService;

    @MockitoBean
    SignupService signupService;

    @Test
    void autoconfiguredMapper_isJackson3JsonMapper() {
        assertThat(jsonMapper.getClass().getName()).startsWith("tools.jackson.");
        assertThat(jsonMapper.getClass().getName()).doesNotContain("com.fasterxml.jackson");
    }

    @Test
    void loginResponse_serializesViaAutoconfiguredMapper() {
        when(authenticationService.authenticate(new LoginDtos.LoginRequest("dev@localhost.local", "password")))
                .thenReturn(Mono.just(new LoginResponse("test-token", "user-001", "dev@localhost.local", "Development User")));

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
