package com.wealth.portfolio.kafka;

import com.wealth.portfolio.TestContainerImages;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.testcontainers.containers.Network;
import org.testcontainers.kafka.ConfluentKafkaContainer;

/** Test-only helper: SASL/PLAIN Kafka broker matching Aiven-style client settings. */
public final class SaslPlainKafkaSupport {

    public static final String USERNAME = "admin";
    public static final String PASSWORD = "admin";
    /** @deprecated Use {@link TestContainerImages#KAFKA} instead. */
    @Deprecated
    public static final String KAFKA_IMAGE = TestContainerImages.KAFKA.asCanonicalNameString();
    public static final String NETWORK_ALIAS = "kafka";
    /**
     * In-network listener registered via {@link ConfluentKafkaContainer#withListener(String)}.
     * Must differ from the default {@code 9092} host-mapped listener to avoid bind conflicts.
     */
    public static final int INTERNAL_PORT = 19092;

    private static final String BROKER_JAAS =
            "org.apache.kafka.common.security.plain.PlainLoginModule required "
                    + "username=\"admin\" password=\"admin\" "
                    + "user_admin=\"admin\" user_test=\"secret\";";

    private SaslPlainKafkaSupport() {}

    /**
     * SASL broker reachable from containers on the shared network at {@link #internalBootstrapServers()}.
     *
     * <p>Host-side tests should still publish via {@code getBootstrapServers()} (localhost mapped port).
     */
    public static ConfluentKafkaContainer createOnNetwork(Network network) {
        ConfluentKafkaContainer kafka =
                new ConfluentKafkaContainer(TestContainerImages.KAFKA) {
                    @Override
                    protected void configure() {
                        super.configure();
                        patchNetworkListenerForSasl(this);
                    }
                };
        return applySaslPlainEnv(kafka)
                .withListener(NETWORK_ALIAS + ":" + INTERNAL_PORT)
                .withNetwork(network);
    }

    /** Standalone SASL/PLAIN broker for host-side clients ({@code getBootstrapServers()}). */
    public static ConfluentKafkaContainer createStandalone() {
        return applySaslPlainEnv(new ConfluentKafkaContainer(TestContainerImages.KAFKA));
    }

    private static ConfluentKafkaContainer applySaslPlainEnv(ConfluentKafkaContainer kafka) {
        return kafka.withEnv(
                        "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
                        "PLAINTEXT:SASL_PLAINTEXT,BROKER:SASL_PLAINTEXT,CONTROLLER:PLAINTEXT")
                .withEnv("KAFKA_SASL_MECHANISM_INTER_BROKER_PROTOCOL", "PLAIN")
                .withEnv("KAFKA_LISTENER_NAME_PLAINTEXT_SASL_ENABLED_MECHANISMS", "PLAIN")
                .withEnv("KAFKA_LISTENER_NAME_BROKER_SASL_ENABLED_MECHANISMS", "PLAIN")
                .withEnv("KAFKA_LISTENER_NAME_BROKER_PLAIN_SASL_JAAS_CONFIG", BROKER_JAAS)
                .withEnv("KAFKA_LISTENER_NAME_PLAINTEXT_PLAIN_SASL_JAAS_CONFIG", BROKER_JAAS);
    }

    /**
     * Testcontainers registers the in-network listener as {@code TC-0} with PLAINTEXT security.
     * Patch it to SASL/PLAIN so slim-image clients exercise the same auth path as production.
     */
    private static void patchNetworkListenerForSasl(ConfluentKafkaContainer kafka) {
        String protocolMap = kafka.getEnvMap().get("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP");
        if (protocolMap == null || !protocolMap.contains("TC-0:PLAINTEXT")) {
            return;
        }
        kafka.getEnvMap()
                .put(
                        "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
                        protocolMap.replace("TC-0:PLAINTEXT", "TC-0:SASL_PLAINTEXT"));
        kafka.getEnvMap().put("KAFKA_LISTENER_NAME_TC-0_SASL_ENABLED_MECHANISMS", "PLAIN");
        kafka.getEnvMap().put("KAFKA_LISTENER_NAME_TC-0_PLAIN_SASL_JAAS_CONFIG", BROKER_JAAS);
    }

    public static String internalBootstrapServers() {
        return NETWORK_ALIAS + ":" + INTERNAL_PORT;
    }

    public static String clientJaasConfig() {
        return "org.apache.kafka.common.security.plain.PlainLoginModule required "
                + "username=\"" + USERNAME + "\" password=\"" + PASSWORD + "\";";
    }

    public static Map<String, Object> saslPlainClientProperties(String bootstrapServers) {
        Map<String, Object> props = new HashMap<>();
        props.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, SecurityProtocol.SASL_PLAINTEXT.name);
        props.put(SaslConfigs.SASL_MECHANISM, "PLAIN");
        props.put(SaslConfigs.SASL_JAAS_CONFIG, clientJaasConfig());
        return props;
    }
}
