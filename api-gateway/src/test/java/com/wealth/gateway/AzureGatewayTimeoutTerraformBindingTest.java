package com.wealth.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class AzureGatewayTimeoutTerraformBindingTest {
    @Test
    void terraformGatewayTimeoutEnvironmentUsesSpringRelaxedBinding() throws Exception {
        Path terraform = Path.of("").toAbsolutePath().resolve("../infrastructure/terraform/azure/main.tf").normalize();
        String module = Files.readString(terraform).split("module \\\"api_gateway\\\"", 2)[1].split("secret_env_vars", 2)[0];
        Map<String, Object> environmentValues = new LinkedHashMap<>();
        Matcher entries = Pattern.compile("(?m)^\\s*(APP_DEMO_LOGIN_RESET_[A-Z_]+|SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT)\\s*=\\s*\\\"([^\\\"]+)\\\"").matcher(module);
        while (entries.find()) environmentValues.put(entries.group(1), entries.group(2));
        assertThat(environmentValues).containsExactlyInAnyOrderEntriesOf(Map.of(
                "APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT", "120s",
                "APP_DEMO_LOGIN_RESET_RESET_TIMEOUT", "30s",
                "APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT", "165s",
                "SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT", "150s"));

        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, Map.of()));
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environmentValues));
        YamlPropertySourceLoader yaml = new YamlPropertySourceLoader();
        yaml.load("application-prod", new ClassPathResource("application-prod.yml"))
                .forEach(environment.getPropertySources()::addLast);
        yaml.load("application", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        Binder binder = Binder.get(environment);

        assertThat(binder.bind("app.demo-login-reset", Bindable.of(DemoLoginResetProperties.class)).get())
                .isEqualTo(new DemoLoginResetProperties(Duration.ofMinutes(30), Duration.ofSeconds(120), Duration.ofSeconds(30), Duration.ofSeconds(165)));
        assertThat(binder.bind("spring.cloud.gateway.server.webflux.httpclient", Bindable.of(HttpClientProperties.class)).get()
                .getResponseTimeout()).isEqualTo(Duration.ofSeconds(150));
    }
}
