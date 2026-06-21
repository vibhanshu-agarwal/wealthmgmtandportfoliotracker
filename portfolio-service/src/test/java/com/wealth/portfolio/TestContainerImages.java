package com.wealth.portfolio;

import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers image catalog for portfolio-service tests.
 *
 * <p>All image versions are pinned to match the Azure production baseline:
 * <ul>
 *   <li>PostgreSQL – Neon 18.4 (production: Neon Postgres 18.4)</li>
 *   <li>Redis – Upstash Redis 8.2.0 (production: Upstash 1.17.11 / Redis 8.2.0)</li>
 *   <li>Kafka – Confluent Platform 8.2.0 (production: Aiven Kafka; exact engine version pending)</li>
 * </ul>
 *
 * <p>Update this file when production managed-service versions change.
 */
public final class TestContainerImages {

    /**
     * PostgreSQL image matching the Neon 18.4 production engine version.
     */
    public static final DockerImageName POSTGRES = DockerImageName.parse("postgres:18.4");

    /** Redis OSS image matching the Upstash Redis 8.2.0 production engine version. */
    public static final DockerImageName REDIS = DockerImageName.parse("redis:8.2.0-alpine");

    /**
     * Confluent Platform Kafka image used for local/test parity with Aiven Kafka.
     * Exact Aiven broker engine version is not exposed by standard metadata;
     * CP 8.2.0 is retained as the local parity target pending Aiven confirmation.
     */
    public static final DockerImageName KAFKA = DockerImageName.parse("confluentinc/cp-kafka:8.2.0");

    private TestContainerImages() {}
}
