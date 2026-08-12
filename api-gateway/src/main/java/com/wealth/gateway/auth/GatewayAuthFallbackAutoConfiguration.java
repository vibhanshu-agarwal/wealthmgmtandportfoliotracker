package com.wealth.gateway.auth;

import com.wealth.gateway.LoginDtos;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Mono;

/**
 * Fallback {@link AuthenticationService}/{@link SignupService} beans for profiles where
 * {@link GatewayAuthDataConfig} does not activate — i.e. no {@code spring.datasource.url} is
 * configured, which is true by design for {@code aws}/{@code azure} today (see
 * {@code ApiGatewayApplication}'s Req 2.5 comment: those profiles never set a datasource and
 * never should).
 *
 * <p>{@code com.wealth.gateway.AuthController} (Task 5, new-user-signup-profile)
 * unconditionally constructor-requires both services. Without this fallback, any profile
 * lacking a datasource fails to boot the entire gateway at context refresh
 * ({@code NoSuchBeanDefinitionException}) — taking every route (portfolio, market-data,
 * insights) down with it, not just auth. These fallbacks let the gateway boot normally and have
 * {@code /api/auth/login}/{@code /api/auth/signup} fail closed with a 503 instead, which
 * {@code AuthController} already maps {@link CredentialStoreUnavailableException} and
 * {@link ProvisioningFailedException} to.
 *
 * <p>Registered as a genuine Spring Boot auto-configuration (not a component-scanned
 * {@code @Configuration}) specifically so {@code @ConditionalOnMissingBean} is evaluated after
 * {@link GatewayAuthDataConfig}'s real beans (component-scanned configurations are always fully
 * processed before deferred auto-configurations) — this guarantees exactly one bean of each type
 * ever exists, never both.
 */
@AutoConfiguration
public class GatewayAuthFallbackAutoConfiguration {

    private static final String NO_DATASOURCE_MESSAGE =
            "No datasource configured for this profile; login/signup is unavailable.";

    @Bean
    @ConditionalOnMissingBean(AuthenticationService.class)
    public AuthenticationService authenticationServiceUnavailable() {
        return new AuthenticationService(null, null, null) {
            @Override
            public Mono<LoginResponse> authenticate(LoginDtos.LoginRequest request) {
                return Mono.error(new CredentialStoreUnavailableException(
                        new IllegalStateException(NO_DATASOURCE_MESSAGE)));
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(SignupService.class)
    public SignupService signupServiceUnavailable() {
        return new SignupService(null, null, null, null) {
            @Override
            public Mono<LoginResponse> provision(SignupDtos.SignupRequest request) {
                return Mono.error(new ProvisioningFailedException(
                        new IllegalStateException(NO_DATASOURCE_MESSAGE)));
            }
        };
    }
}
