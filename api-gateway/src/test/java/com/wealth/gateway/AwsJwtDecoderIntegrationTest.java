package com.wealth.gateway;

import com.wealth.gateway.auth.AuthenticationService;
import com.wealth.gateway.auth.LoginResponse;
import com.wealth.gateway.auth.SignupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Production-profile JWT round-trip: AuthController issues HS256 and the AWS-profile
 * decoder accepts the same token on protected routes.
 *
 * <p>{@link AuthenticationService}/{@link SignupService} are mocked (Task 5, new-user-signup-
 * profile fix round 1): {@code AuthController} now constructor-requires them, but this class's
 * subject is the JWT round-trip through the AWS {@code ReactiveJwtDecoder}, not real per-user
 * credential validation — there's no seeded row for the old hardcoded demo@example.com/
 * demo-password credential (Task 1's V14/V15 seed only creates demo@wealthtracker.dev,
 * dev@local, e2e-test-user@vibhanshu-ai-portfolio.dev), so the mocked
 * {@code authenticationService.authenticate(...)} returns a canned {@link LoginResponse} whose
 * token is minted by the real (unconditionally-available) {@link JwtSigner} bean — preserving
 * "AuthController issues HS256" coverage while sidestepping the missing DB row.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"prod", "aws"})
class AwsJwtDecoderIntegrationTest {

    private static final String DEMO_USER_ID = "00000000-0000-0000-0000-000000000e2e";
    private static final String DEMO_EMAIL = "demo@example.com";
    private static final String DEMO_PASSWORD = "demo-password";
    private static final String DEMO_NAME = "Demo User";

    // This test activates the "prod" profile, and application-prod.yml declares
    // spring.datasource.url/username/password as ${SPRING_DATASOURCE_URL}/etc with NO default
    // (Req 2.5 — missing values must fail startup loudly). GatewayAuthDataConfig's
    // @ConditionalOnProperty gate must resolve that property during context refresh, so an
    // ephemeral real Postgres is required here purely to make that resolution succeed — this
    // test exercises the AWS JWT decoder, not auth/datasource logic. (AuthenticationService/
    // SignupService are mocked below regardless of whether GatewayAuthDataConfig activates.)
    @Container
    @SuppressWarnings({"resource", "rawtypes"})
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(TestContainerImages.POSTGRES)
                    .withDatabaseName("portfolio_db")
                    .withUsername("wealth_user")
                    .withPassword("wealth_pass");

    @DynamicPropertySource
    static void authProperties(DynamicPropertyRegistry registry) {
        registry.add("auth.jwt.secret", () -> TestJwtFactory.TEST_SECRET);

        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @MockitoBean
    AuthenticationService authenticationService;

    @MockitoBean
    SignupService signupService;

    @Autowired
    JwtSigner jwtSigner;

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
    void loginTokenIssuedUnderAwsProfileIsAcceptedByGateway() throws Exception {
        String signedToken = jwtSigner.signHs256(DEMO_USER_ID, DEMO_EMAIL, DEMO_NAME, false);
        when(authenticationService.authenticate(new LoginDtos.LoginRequest(DEMO_EMAIL, DEMO_PASSWORD)))
                .thenReturn(Mono.just(new LoginResponse(signedToken, DEMO_USER_ID, DEMO_EMAIL, DEMO_NAME)));

        LoginDtos.LoginResponse login = webTestClient.post()
                .uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", DEMO_EMAIL, "password", DEMO_PASSWORD))
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