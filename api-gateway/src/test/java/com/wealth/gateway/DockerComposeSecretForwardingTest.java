package com.wealth.gateway;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bug Condition Exploration Test — Property 1a: JWT Secret Forwarding in Docker Compose.
 *
 * <p>Parses {@code docker-compose.yml} from the project root and verifies that the
 * {@code api-gateway} service's environment block contains {@code AUTH_JWT_SECRET}.
 * This is a deterministic structural test — no Spring context needed.
 *
 * <p><b>Expected outcome on unfixed code:</b> This test FAILS because
 * {@code AUTH_JWT_SECRET} is not present in the api-gateway environment block,
 * confirming the bug condition where the container never receives the CI secret.
 *
 * <p><b>Validates: Requirements 1.1, 2.1</b>
 */
class DockerComposeSecretForwardingTest {

    /**
     * The docker-compose.yml lives at the repository root. From the api-gateway module
     * test working directory, we navigate up one level.
     */
    private static final Path DOCKER_COMPOSE_PATH = Path.of("../docker-compose.yml");

    @Test
    @SuppressWarnings("unchecked")
    void apiGatewayEnvironmentContainsAuthJwtSecret() throws IOException {
        assertThat(DOCKER_COMPOSE_PATH)
                .as("docker-compose.yml must exist at the project root")
                .exists();

        Map<String, Object> compose;
        try (InputStream is = Files.newInputStream(DOCKER_COMPOSE_PATH)) {
            compose = new Yaml().load(is);
        }

        assertThat(compose).as("docker-compose.yml must have a 'services' key")
                .containsKey("services");

        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        assertThat(services).as("docker-compose.yml must have an 'api-gateway' service")
                .containsKey("api-gateway");

        Map<String, Object> apiGateway = (Map<String, Object>) services.get("api-gateway");
        assertThat(apiGateway).as("api-gateway service must have an 'environment' block")
                .containsKey("environment");

        Map<String, Object> environment = (Map<String, Object>) apiGateway.get("environment");
        assertThat(environment)
                .as("api-gateway environment must contain AUTH_JWT_SECRET so the CI runner's "
                    + "secret is forwarded into the container (bug condition: container never "
                    + "receives AUTH_JWT_SECRET → falls back to hardcoded default → JWT mismatch)")
                .containsKey("AUTH_JWT_SECRET");
    }
}
