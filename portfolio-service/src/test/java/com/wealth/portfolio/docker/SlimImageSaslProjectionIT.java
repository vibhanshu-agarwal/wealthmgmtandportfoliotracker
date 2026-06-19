package com.wealth.portfolio.docker;

import static org.assertj.core.api.Assertions.assertThat;

import com.wealth.market.events.PriceUpdatedEvent;
import com.wealth.portfolio.kafka.SaslPlainKafkaSupport;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Task 3.4 — H1 causal-chain closer: slim jlink JRE + SASL/PLAIN broker + live Kafka consumer.
 *
 * <p>Aligns with {@link SlimImageHealthIT} boot conventions ({@code aws} profile, Postgres/Redis on
 * the shared network) but enables the Kafka listener and points it at a SASL/PLAIN broker reached
 * via the shared Testcontainers network alias ({@code kafka:9092}), matching {@code docker-compose.yml}.
 */
@Tag("slim-image")
@Testcontainers
class SlimImageSaslProjectionIT {

    private static final java.nio.file.Path REPO_ROOT = java.nio.file.Path.of(System.getProperty("repo.root"));
    private static final DockerImageName SLIM_IMAGE =
            DockerImageName.parse(System.getProperty("slim.test.image"));
    private static final int APP_PORT = 8081;
    private static final Duration AWAIT_TIMEOUT = Duration.ofMinutes(3);
    private static final Network NETWORK = Network.newNetwork();

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("postgres")
                    .withDatabaseName("portfolio_db")
                    .withUsername("wealth_user")
                    .withPassword("wealth_pass");

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("redis")
                    .withExposedPorts(6379);

    @Container
    @SuppressWarnings("resource")
    static final ConfluentKafkaContainer kafka = SaslPlainKafkaSupport.createOnNetwork(NETWORK);

    static GenericContainer<?> portfolio;

    @BeforeAll
    static void startPortfolioContainer() {
        String bootstrap = SaslPlainKafkaSupport.internalBootstrapServers();

        portfolio =
                new GenericContainer<>(SLIM_IMAGE)
                        .withNetwork(NETWORK)
                        .withExposedPorts(APP_PORT)
                        .withEnv("SPRING_PROFILES_ACTIVE", "aws")
                        .withEnv(
                                "SPRING_DATASOURCE_URL",
                                "jdbc:postgresql://postgres:5432/portfolio_db?options=-c%20timezone=Asia/Kolkata")
                        .withEnv("SPRING_DATASOURCE_USERNAME", "wealth_user")
                        .withEnv("SPRING_DATASOURCE_PASSWORD", "wealth_pass")
                        .withEnv("SPRING_DATA_REDIS_URL", "redis://redis:6379")
                        .withEnv("SPRING_KAFKA_BOOTSTRAP_SERVERS", bootstrap)
                        .withEnv("SPRING_KAFKA_PROPERTIES_SECURITY_PROTOCOL", "SASL_PLAINTEXT")
                        .withEnv("SPRING_KAFKA_PROPERTIES_SASL_MECHANISM", "PLAIN")
                        .withEnv(
                                "SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG",
                                SaslPlainKafkaSupport.clientJaasConfig())
                        .withEnv("SPRING_KAFKA_LISTENER_AUTO_STARTUP", "true")
                        .withEnv("MANAGEMENT_HEALTH_KAFKA_ENABLED", "false")
                        .withEnv("MANAGEMENT_TRACING_EXPORT_ENABLED", "false")
                        .withEnv("MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED", "false")
                        .dependsOn(postgres, redis, kafka)
                        .withStartupTimeout(Duration.ofMinutes(10))
                        .waitingFor(
                                Wait.forHttp("/actuator/health")
                                        .forPort(APP_PORT)
                                        .forStatusCode(200)
                                        .withStartupTimeout(Duration.ofMinutes(10)));
        try {
            portfolio.start();
        } catch (RuntimeException ex) {
            throw new AssertionError("Slim portfolio container failed to start:\n" + portfolio.getLogs(), ex);
        }
    }

    @AfterAll
    static void stopPortfolioContainer() {
        if (portfolio != null) {
            portfolio.stop();
        }
    }

    @Test
    void slimJre_canAuthenticateToSaslPlainBroker() throws Exception {
        portfolio.copyFileToContainer(
                org.testcontainers.utility.MountableFile.forHostPath(
                        java.nio.file.Path.of(REPO_ROOT.toString())
                                .resolve(
                                        "portfolio-service/build/classes/java/test/com/wealth/portfolio/docker/SlimJreSaslKafkaProbe.class")),
                "/probe/com/wealth/portfolio/docker/SlimJreSaslKafkaProbe.class");

        String bootstrap = SaslPlainKafkaSupport.internalBootstrapServers();
        var result =
                portfolio.execInContainer(
                        "/opt/java/bin/java",
                        "-cp",
                        "/probe",
                        "com.wealth.portfolio.docker.SlimJreSaslKafkaProbe",
                        bootstrap);
        assertThat(result.getExitCode())
                .as("probe stderr: %s stdout: %s", result.getStderr(), result.getStdout())
                .isZero();
        assertThat(result.getStdout()).contains("KAFKA_SASL_OK");
    }

    @Test
    void slimImage_wellFormedEventOverSaslPlain_projectsToReadModel() throws Exception {
        String ticker = "SLIM_" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        Instant observedAt = Instant.parse("2026-06-18T12:00:00Z");

        Map<String, Object> producerProps = new HashMap<>(SaslPlainKafkaSupport.saslPlainClientProperties(
                kafka.getBootstrapServers()));
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
        producerProps.put(JacksonJsonSerializer.ADD_TYPE_INFO_HEADERS, true);

        KafkaTemplate<String, PriceUpdatedEvent> producer =
                new KafkaTemplate<>(
                        new DefaultKafkaProducerFactory<>(
                                producerProps, new StringSerializer(), new JacksonJsonSerializer<>()));
        try {
            producer.send(
                            "market-prices",
                            ticker,
                            new PriceUpdatedEvent(
                                    ticker,
                                    new BigDecimal("150.25"),
                                    "USD",
                                    observedAt,
                                    null,
                                    null))
                    .get();
        } finally {
            producer.destroy();
        }

        JdbcTemplate jdbc = jdbcTemplateForPostgres();

        Awaitility.await()
                .atMost(AWAIT_TIMEOUT)
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(
                        () -> {
                            Integer priceCount =
                                    jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM market_prices WHERE ticker = ?",
                                            Integer.class,
                                            ticker);
                            assertThat(priceCount)
                                    .as("portfolio logs (kafka/infra):\n%s", kafkaRelatedLogs())
                                    .isEqualTo(1);

                            // Slim container uses Asia/Kolkata on TIMESTAMP (no TZ); match by ticker only.
                            // observed_at identity is covered by PriceUpdatedEventSaslTransportIT on full JRE.
                            Integer historyCount =
                                    jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM market_price_history WHERE ticker = ?",
                                            Integer.class,
                                            ticker);
                            assertThat(historyCount)
                                    .as("portfolio logs (kafka/infra):\n%s", kafkaRelatedLogs())
                                    .isEqualTo(1);
                        });
    }

    private static String kafkaRelatedLogs() {
        String logs = portfolio.getLogs();
        return logs.lines()
                .filter(
                        line ->
                                line.contains("kafka")
                                        || line.contains("Kafka")
                                        || line.contains("INFRA-")
                                        || line.contains("Price update")
                                        || line.contains("ERROR")
                                        || line.contains("Exception"))
                .reduce((a, b) -> a + System.lineSeparator() + b)
                .orElse(logs.substring(Math.max(0, logs.length() - 4000)));
    }

    private static JdbcTemplate jdbcTemplateForPostgres() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        String jdbcUrl = postgres.getJdbcUrl();
        if (!jdbcUrl.contains("timezone=")) {
            jdbcUrl +=
                    (jdbcUrl.contains("?") ? "&" : "?") + "options=-c%20timezone=Asia/Kolkata";
        }
        dataSource.setUrl(jdbcUrl);
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return new JdbcTemplate(dataSource);
    }
}
