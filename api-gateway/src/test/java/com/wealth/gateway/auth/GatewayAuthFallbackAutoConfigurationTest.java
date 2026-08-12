package com.wealth.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.wealth.gateway.JwtSigner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Regression test for {@link GatewayAuthFallbackAutoConfiguration}'s fail-closed back-off
 * behavior. That class relies entirely on Spring Boot's documented guarantee that
 * component-scanned {@code @Configuration} classes (like {@link GatewayAuthDataConfig}) are
 * always fully processed BEFORE deferred {@code @AutoConfiguration} classes are evaluated, so its
 * {@code @ConditionalOnMissingBean(AuthenticationService.class)} / {@code
 * @ConditionalOnMissingBean(SignupService.class)} fallback beans back off exactly when a real
 * datasource is configured. Nothing previously asserted this mechanism directly — the final
 * whole-branch review flagged that the identical "trust the auto-configuration ordering" pattern
 * had already silently masked a real two-cloud production misconfiguration (Fix 1: neither
 * Terraform module ever set {@code spring.datasource.url} for api-gateway, so auth would have
 * quietly degraded to permanent 503s with no startup failure).
 *
 * <p>Redis/Testcontainers-free {@link ApplicationContextRunner} test: Hikari's connection pool
 * initializes lazily (Spring Boot's {@code DataSourceBuilder} constructs {@link
 * com.zaxxer.hikari.HikariDataSource} via its no-arg constructor and only creates the underlying
 * pool on first {@code getConnection()}), so a syntactically valid but unreachable JDBC URL is
 * sufficient to exercise {@link GatewayAuthDataConfig} without a real database — mirrors {@code
 * AuthRateLimitFilterBeanWiringTest}'s technique of proving which concrete bean wins by asserting
 * on the real classes rather than hand-picking a bean.
 */
class GatewayAuthFallbackAutoConfigurationTest {

    private static final String UNREACHABLE_JDBC_URL = "jdbc:postgresql://localhost:59999/unreachable_test_db";
    private static final String TEST_JWT_SECRET = "test-jwt-secret-value-that-is-at-least-32-bytes-long";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    // ConfigurationPropertiesAutoConfiguration registers the
                    // ConfigurationPropertiesBindingPostProcessor that actually binds
                    // GatewayAuthDataConfig's @ConfigurationProperties("spring.datasource") beans —
                    // a bare ApplicationContextRunner does not wire that up on its own.
                    .withConfiguration(AutoConfigurations.of(
                            GatewayAuthFallbackAutoConfiguration.class, ConfigurationPropertiesAutoConfiguration.class));

    @Test
    void realBeansWinWhenDatasourceIsConfigured() {
        runner
                // GatewayAuthDataConfig here plays the same role component-scanning plays in the
                // real application: a user-registered @Configuration processed before the
                // deferred GatewayAuthFallbackAutoConfiguration is evaluated.
                .withUserConfiguration(GatewayAuthDataConfig.class, PasswordHasherConfig.class, JwtSigner.class)
                .withPropertyValues(
                        "spring.datasource.url=" + UNREACHABLE_JDBC_URL,
                        "spring.datasource.username=test_user",
                        "spring.datasource.password=test_password",
                        "auth.jwt.secret=" + TEST_JWT_SECRET)
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    assertThat(context.getBean(AuthenticationService.class).getClass())
                            .as("GatewayAuthDataConfig's real AuthenticationService bean must win over the "
                                    + "fail-closed fallback stub when spring.datasource.url is configured")
                            .isEqualTo(AuthenticationService.class);

                    assertThat(context.getBean(SignupService.class).getClass())
                            .as("GatewayAuthDataConfig's real SignupService bean must win over the fail-closed "
                                    + "fallback stub when spring.datasource.url is configured")
                            .isEqualTo(SignupService.class);
                });
    }

    @Test
    void fallbackStubsWinWhenNoDatasourceIsConfigured() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();

            assertThat(context.getBean(AuthenticationService.class).getClass())
                    .as("Without spring.datasource.url, GatewayAuthFallbackAutoConfiguration's fail-closed "
                            + "AuthenticationService stub must activate instead of the real class — proving "
                            + "auth degrades to a 503 rather than failing to boot or resolving no bean at all")
                    .isNotEqualTo(AuthenticationService.class);

            assertThat(context.getBean(SignupService.class).getClass())
                    .as("Without spring.datasource.url, GatewayAuthFallbackAutoConfiguration's fail-closed "
                            + "SignupService stub must activate instead of the real class")
                    .isNotEqualTo(SignupService.class);
        });
    }
}
