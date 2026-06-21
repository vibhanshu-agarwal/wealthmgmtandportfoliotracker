package com.wealth.market;

import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers image catalog for market-data-service tests.
 *
 * <p>All image versions are pinned to match the Azure production baseline:
 * <ul>
 *   <li>MongoDB – Atlas 8.0.26 (production: MongoDB Atlas 8.0.26)</li>
 *   <li>Kafka – Confluent Platform 8.2.0 (production: Aiven Kafka; exact engine version pending)</li>
 * </ul>
 *
 * <p>Update this file when production managed-service versions change.
 */
public final class TestContainerImages {

    /**
     * MongoDB image matching the Atlas 8.0.26 production engine version.
     * Uses the nearest official 8.0.x patch tag.
     */
    public static final DockerImageName MONGO = DockerImageName.parse("mongo:8.0.26");

    /**
     * Confluent Platform Kafka image used for local/test parity with Aiven Kafka.
     * Exact Aiven broker engine version is not exposed by standard metadata;
     * CP 8.2.0 is retained as the local parity target pending Aiven confirmation.
     */
    public static final DockerImageName KAFKA = DockerImageName.parse("confluentinc/cp-kafka:8.2.0");

    private TestContainerImages() {}
}
