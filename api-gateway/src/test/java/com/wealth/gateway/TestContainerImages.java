package com.wealth.gateway;

import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers image catalog for api-gateway tests.
 *
 * <p>All image versions are pinned to match the Azure production baseline:
 * <ul>
 *   <li>Redis – Upstash Redis 8.2.0 (production: Upstash 1.17.11 / Redis 8.2.0)</li>
 *   <li>Postgres – matches the local docker-compose default (postgres:18.4) and
 *       portfolio-service's own {@code TestContainerImages.POSTGRES}</li>
 * </ul>
 *
 * <p>Update this file when production managed-service versions change.
 */
public final class TestContainerImages {

    /** Redis OSS image matching the Upstash Redis 8.2.0 production engine version. */
    public static final DockerImageName REDIS = DockerImageName.parse("redis:8.2.0-alpine");

    /** Postgres image matching the local docker-compose / portfolio-service baseline. */
    public static final DockerImageName POSTGRES = DockerImageName.parse("postgres:18.4");

    private TestContainerImages() {}
}
