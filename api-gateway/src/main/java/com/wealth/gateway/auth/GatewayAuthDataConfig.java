package com.wealth.gateway.auth;

import com.wealth.gateway.JwtSigner;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/**
 * Explicit JDBC bean wiring for the auth data layer, standing in for the
 * spring-boot-starter-jdbc autoconfiguration that {@code ApiGatewayApplication} excludes.
 *
 * <p>That exclusion exists because DataSourceAutoConfiguration, if left enabled, requires a
 * resolvable {@code spring.datasource.url} for every profile's ApplicationContext — not only
 * {@code local}/{@code prod}, which are the only profiles that actually define one (Req 2.5).
 * This whole configuration class is gated on {@code spring.datasource.url} being present so it
 * activates only where a real datasource is configured; under {@code aws}/{@code azure} (which
 * never set it) none of these beans are created at all.
 */
@Configuration
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class GatewayAuthDataConfig {

    @Bean
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties gatewayDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource gatewayDataSource(DataSourceProperties gatewayDataSourceProperties) {
        return gatewayDataSourceProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    public NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource gatewayDataSource) {
        return new NamedParameterJdbcTemplate(gatewayDataSource);
    }

    // Explicit @Bean rather than @Repository component-scanning: UserCredentialRepository must
    // only be created where this class is active (i.e. spring.datasource.url is set), never
    // eagerly in aws/azure-profile contexts that have no datasource at all.
    @Bean
    public UserCredentialRepository userCredentialRepository(NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
        return new UserCredentialRepository(namedParameterJdbcTemplate);
    }

    @Bean
    public PlatformTransactionManager gatewayTransactionManager(DataSource gatewayDataSource) {
        return new DataSourceTransactionManager(gatewayDataSource);
    }

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager gatewayTransactionManager) {
        return new TransactionTemplate(gatewayTransactionManager);
    }

    // Explicit @Bean rather than @Service component-scanning, for the same reason as
    // userCredentialRepository() above: AuthenticationService/SignupService depend on
    // UserCredentialRepository (and SignupService also on TransactionTemplate), which only
    // exist while this class is active. Registering them here — instead of relying on
    // component-scan + @ConditionalOnBean, which Spring only evaluates reliably for
    // auto-configuration classes, not regular @Configuration/@Component ordering — guarantees
    // they are created if and only if this class is.
    @Bean
    public AuthenticationService authenticationService(
            UserCredentialRepository userCredentialRepository,
            PasswordEncoder passwordEncoder,
            JwtSigner jwtSigner) {
        return new AuthenticationService(userCredentialRepository, passwordEncoder, jwtSigner);
    }

    @Bean
    public SignupService signupService(
            UserCredentialRepository userCredentialRepository,
            PasswordEncoder passwordEncoder,
            JwtSigner jwtSigner,
            TransactionTemplate transactionTemplate) {
        return new SignupService(userCredentialRepository, passwordEncoder, jwtSigner, transactionTemplate);
    }
}
