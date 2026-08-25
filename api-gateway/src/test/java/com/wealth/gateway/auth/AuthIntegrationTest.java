package com.wealth.gateway.auth;

import com.nimbusds.jose.JOSEException;
import com.wealth.gateway.JwtSigner;
import com.wealth.gateway.TestContainerImages;
import com.wealth.gateway.TestJwtFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

/**
 * .kiro/specs/new-user-signup-profile, Requirement 10 (10.1-10.3, 10.4-10.7, 10.10-10.12).
 *
 * <p>Runs the real Flyway migrations from {@code portfolio-service} against a Testcontainers
 * Postgres, boots the full gateway {@code ApplicationContext} ({@code RANDOM_PORT}), and drives
 * {@code /api/auth/login} / {@code /api/auth/signup} plus authenticated calls through
 * {@code ReadOnlyEnforcementFilter} via {@link WebTestClient}.
 *
 * <p>{@code spring.datasource.url/username/password} MUST be set (below, via
 * {@code @DynamicPropertySource}) before the context refreshes: {@code AuthController}'s real
 * {@code AuthenticationService}/{@code SignupService} beans come from
 * {@code GatewayAuthDataConfig}, which is {@code @ConditionalOnProperty(prefix =
 * "spring.datasource", name = "url")}. Without that property, {@code AuthController} would wire
 * up {@code GatewayAuthFallbackAutoConfiguration}'s fail-closed stubs instead (always 503),
 * failing every test in this class confusingly rather than obviously.
 *
 * <p>Run via: {@code ./gradlew :api-gateway:integrationTest}
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class AuthIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(TestContainerImages.POSTGRES)
            .withDatabaseName("portfolio_db")
            .withUsername("wealth_user")
            .withPassword("wealth_pass");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                // Path relative to api-gateway/ (this module's working directory under Gradle —
                // confirmed by RateLimitConfigurationGuardrailTest's identical
                // Path.of("src","main","resources") convention, which already runs successfully
                // against api-gateway's own module-relative resources). api-gateway's test
                // classpath does not include portfolio-service's db/migration resources, so
                // Flyway must be pointed at the sibling module's source tree directly.
                .locations("filesystem:../portfolio-service/src/main/resources/db/migration")
                .load()
                .migrate();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("auth.jwt.secret", () -> TestJwtFactory.TEST_SECRET);
    }

    @LocalServerPort int port;

    // GatewayAuthDataConfig registers only a NamedParameterJdbcTemplate bean (named
    // "gatewayDataSource" + "namedParameterJdbcTemplate") — no plain JdbcTemplate bean exists in
    // this context (JdbcTemplateAutoConfiguration is excluded in ApiGatewayApplication; see its
    // Req 2.5 comment). Autowiring the DataSource directly and wrapping our own JdbcTemplate here
    // is test-only plumbing — it does not require adding a JdbcTemplate bean to production wiring
    // just to satisfy this test's row-count assertions.
    @Autowired DataSource dataSource;
    private JdbcTemplate jdbcTemplate;

    // Wraps the REAL JwtSigner bean (shared by both AuthenticationService and SignupService) so a
    // single test can force a JOSEException out of signHs256(...) — the exact failure point Req
    // 10.3 requires proving a rollback for (both inserts already succeeded; signing is the last
    // step before the transaction commits). Spring resets this spy's stubbing after each test
    // method (MockReset.AFTER is the default for @MockitoSpyBean), and the stub below additionally
    // matches on this test's specific email so it can never leak into any other test's calls even
    // if reset semantics ever changed.
    @MockitoSpyBean JwtSigner jwtSigner;

    @BeforeEach
    void setUpJdbcTemplate() {
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10)).build();
    }

    // ---- Requirement 10.1, 10.2 — signup provisions both rows; sub matched by requireUserExists ----

    @Test
    void signupProvisionsUsersAndUserCredentialsRows() {
        String email = "new-user-1@example.com";

        var response = client().post().uri("/api/auth/signup")
                .bodyValue(java.util.Map.of("email", email, "password", "a-strong-password-123", "name", "New User"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(java.util.Map.class)
                .returnResult().getResponseBody();

        String userId = (String) response.get("userId");
        Integer userCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE id = ?::uuid", Integer.class, userId);
        Integer credCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_credentials WHERE user_id = ?::uuid", Integer.class, userId);
        Integer portfolioCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM portfolios WHERE user_id = ?", Integer.class, userId);
        assertThat(userCount).isEqualTo(1);
        assertThat(credCount).isEqualTo(1);
        assertThat(portfolioCount).isEqualTo(1);
    }

    // ---- Requirement 10.3 — forced provisioning-transaction failure persists zero rows ----

    @Test
    void signupRollsBackBothRowsWhenTokenSigningFails() throws JOSEException {
        String email = "forced-jose-failure@example.com";

        // SignupService.provision() inserts the users row, then the user_credentials row, and
        // only THEN calls jwtSigner.signHs256(...) to mint the response token — the last step
        // before the transaction commits. Forcing that call to throw a JOSEException (matching
        // the real exception type SignupService already catches: `catch (JOSEException e) {
        // status.setRollbackOnly(); throw new ProvisioningFailedException(e); }`) exercises the
        // genuine rollback path against a real Postgres transaction, not a mock-only unit test
        // (Task 3's SignupServiceTest already covers the mock-level behavior).
        //
        // Matching on this test's specific email (rather than stubbing unconditionally) keeps the
        // failure injection scoped to this one signup call — every other test's login/signup
        // calls use different emails and hit the real signHs256 implementation unaffected.
        doThrow(new JOSEException("forced failure for Req 10.3 rollback test"))
                .when(jwtSigner).signHs256(anyString(), eq(email), anyString(), anyBoolean());

        // portfolios has no email column and the generated userId is never returned on a 503,
        // so capture the table count before the failing signup and assert it is unchanged after.
        Integer portfoliosBefore = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM portfolios", Integer.class);

        client().post().uri("/api/auth/signup")
                .bodyValue(java.util.Map.of("email", email, "password", "a-strong-password-123", "name", "Rollback User"))
                .exchange()
                // AuthController.signup() maps ProvisioningFailedException -> 503 SERVICE_UNAVAILABLE
                // (provisioningFailed()) — never 201.
                .expectStatus().isEqualTo(503);

        Integer userCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE lower(email) = lower(?)", Integer.class, email);
        Integer credCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_credentials WHERE lower(email) = lower(?)", Integer.class, email);
        Integer portfoliosAfter = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM portfolios", Integer.class);
        assertThat(userCount).as("users row must not persist when the transaction rolls back").isZero();
        assertThat(credCount).as("user_credentials row must not persist when the transaction rolls back").isZero();
        assertThat(portfoliosAfter)
                .as("portfolios row must not persist when the transaction rolls back")
                .isEqualTo(portfoliosBefore);
    }

    // ---- Requirement 10.7 — duplicate-email signup returns 409, row count stays 1 ----

    @Test
    void duplicateEmailSignupReturns409AndRowCountStaysOne() {
        String email = "dup-user@example.com";
        var body = java.util.Map.of("email", email, "password", "a-strong-password-123", "name", "Dup User");

        client().post().uri("/api/auth/signup").bodyValue(body).exchange().expectStatus().isCreated();
        client().post().uri("/api/auth/signup").bodyValue(body).exchange().expectStatus().isEqualTo(409);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_credentials WHERE lower(email) = lower(?)", Integer.class, email);
        assertThat(count).isEqualTo(1);
    }

    // ---- Requirement 10.4 — correct login returns 200 with non-empty token ----

    @Test
    void correctLoginReturns200WithNonEmptyToken() {
        String email = "login-ok@example.com";
        client().post().uri("/api/auth/signup")
                .bodyValue(java.util.Map.of("email", email, "password", "a-strong-password-123", "name", "Login Ok"))
                .exchange().expectStatus().isCreated();

        var response = client().post().uri("/api/auth/login")
                .bodyValue(java.util.Map.of("email", email, "password", "a-strong-password-123"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(java.util.Map.class)
                .returnResult().getResponseBody();

        assertThat((String) response.get("token")).isNotBlank();
    }

    // ---- Requirement 10.5, 10.6 — wrong password / unknown email both 401, byte-identical bodies ----

    @Test
    void wrongPasswordAndUnknownEmailReturnByteIdenticalUniform401() {
        String email = "wrongpw-user@example.com";
        client().post().uri("/api/auth/signup")
                .bodyValue(java.util.Map.of("email", email, "password", "a-strong-password-123", "name", "WP User"))
                .exchange().expectStatus().isCreated();

        byte[] wrongPasswordBody = client().post().uri("/api/auth/login")
                .bodyValue(java.util.Map.of("email", email, "password", "totally-wrong-password"))
                .exchange().expectStatus().isEqualTo(401)
                .expectBody().returnResult().getResponseBody();

        byte[] unknownEmailBody = client().post().uri("/api/auth/login")
                .bodyValue(java.util.Map.of("email", "no-such-user@example.com", "password", "whatever12345"))
                .exchange().expectStatus().isEqualTo(401)
                .expectBody().returnResult().getResponseBody();

        assertThat(wrongPasswordBody).isEqualTo(unknownEmailBody);
    }

    // ---- Requirement 10.10, 10.11 — demo read-only write blocked, chat allowed ----

    @Test
    void demoAccountWriteToPortfolioIsRejectedWith403AndDataUnchanged() {
        String demoToken = TestJwtFactory.mint("00000000-0000-0000-0000-0000000d3110", Duration.ofHours(1),
                TestJwtFactory.TEST_SECRET, java.util.Map.of("ro", true));

        // Checks the mutation had zero effect directly against this same Postgres instance's
        // asset_holdings table, rather than trusting the 403 status code alone.
        //
        // Note this assertion alone is weaker than it looks: the downstream proxy target does not
        // exist in this test context, so a request that leaked past the filter would die on
        // connection-refused and still leave the table unchanged. That is exactly how the
        // switchIfEmpty double-subscription bug hid here. ReadOnlyEnforcementFilterChainTest is
        // what actually pins "the downstream chain is never subscribed on a blocked write".
        Integer holdingsBefore = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM asset_holdings h JOIN portfolios p ON p.id = h.portfolio_id "
                        + "WHERE p.user_id = '00000000-0000-0000-0000-0000000d3110'",
                Integer.class);

        client().post().uri("/api/portfolio/holdings")
                .header("Authorization", "Bearer " + demoToken)
                .bodyValue(java.util.Map.of("assetTicker", "AAPL", "quantity", 1))
                .exchange()
                .expectStatus().isEqualTo(403);

        Integer holdingsAfter = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM asset_holdings h JOIN portfolios p ON p.id = h.portfolio_id "
                        + "WHERE p.user_id = '00000000-0000-0000-0000-0000000d3110'",
                Integer.class);
        assertThat(holdingsAfter).as("rejected write must leave the demo portfolio's holdings unchanged")
                .isEqualTo(holdingsBefore);
    }

    @Test
    void demoAccountChatPostIsAllowedNotForbidden() {
        String demoToken = TestJwtFactory.mint("00000000-0000-0000-0000-0000000d3110", Duration.ofHours(1),
                TestJwtFactory.TEST_SECRET, java.util.Map.of("ro", true));

        var status = client().post().uri("/api/chat/message")
                .header("Authorization", "Bearer " + demoToken)
                .bodyValue(java.util.Map.of("message", "hello"))
                .exchange()
                .returnResult(Void.class).getStatus();

        // Proxied to a non-existent upstream in this test context (no insight-service running) —
        // the assertion is that ReadOnlyEnforcementFilter did NOT reject it (never 403), matching
        // the RateLimitingIntegrationTest precedent of asserting "not blocked" rather than a
        // specific downstream status when no real upstream is wired.
        assertThat(status.value()).isNotEqualTo(403);
    }

    // ---- Requirement 10.12 — demo UUID owns the showcase portfolio with non-empty holdings ----

    @Test
    void demoUuidOwnsShowcasePortfolioAndDevUserHasEmptyPrimary() {
        Integer devOwned = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM portfolios WHERE user_id = '00000000-0000-0000-0000-000000000001'",
                Integer.class);
        Integer devHoldings = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM asset_holdings h JOIN portfolios p ON p.id = h.portfolio_id "
                        + "WHERE p.user_id = '00000000-0000-0000-0000-000000000001'",
                Integer.class);
        Integer demoHoldings = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM asset_holdings h JOIN portfolios p ON p.id = h.portfolio_id "
                        + "WHERE p.user_id = '00000000-0000-0000-0000-0000000d3110'",
                Integer.class);
        assertThat(devOwned).as("V20 backfill gives the dev user one primary portfolio").isEqualTo(1);
        assertThat(devHoldings).as("dev primary portfolio must remain empty").isZero();
        assertThat(demoHoldings).isGreaterThan(0);
    }
}
