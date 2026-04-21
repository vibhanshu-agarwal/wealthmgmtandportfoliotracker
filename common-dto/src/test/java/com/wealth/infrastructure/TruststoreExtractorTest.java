package com.wealth.infrastructure;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.LINUX)
class TruststoreExtractorTest {

    private static final String RESOURCE_NAME = "truststore-test.txt";
    private static final Path EXTRACTED_PATH = Path.of("/tmp", RESOURCE_NAME);
    private static final String KAFKA_PROPERTY = "KAFKA_TRUSTSTORE_PATH";
    private static final String REDIS_PROPERTY = "REDIS_TRUSTSTORE_PATH";

    @AfterEach
    void tearDown() throws IOException {
        System.clearProperty(KAFKA_PROPERTY);
        System.clearProperty(REDIS_PROPERTY);
        Files.deleteIfExists(EXTRACTED_PATH);
    }

    @Test
    void extractSetsKafkaTruststorePathAndCopiesResource() throws IOException {
        TruststoreExtractor.extract(RESOURCE_NAME, KAFKA_PROPERTY);

        String actualProperty = System.getProperty(KAFKA_PROPERTY);
        assertNotNull(actualProperty);
        assertEquals("file:" + EXTRACTED_PATH.toAbsolutePath(), actualProperty);
        assertTrue(Files.exists(EXTRACTED_PATH));
        assertEquals("dummy-test-truststore", Files.readString(EXTRACTED_PATH).trim());
    }

    @Test
    void extractSupportsLeadingSlashForRedisTruststorePath() throws IOException {
        TruststoreExtractor.extract("/" + RESOURCE_NAME, REDIS_PROPERTY);

        String actualProperty = System.getProperty(REDIS_PROPERTY);
        assertNotNull(actualProperty);
        assertEquals("file:" + EXTRACTED_PATH.toAbsolutePath(), actualProperty);
        assertTrue(Files.exists(EXTRACTED_PATH));
        assertEquals("dummy-test-truststore", Files.readString(EXTRACTED_PATH).trim());
    }

    @Test
    void extractWithMissingResourceDoesNotSetPropertyOrCreateFile() throws IOException {
        String missingResource = "missing-truststore.jks";
        Path missingPath = Path.of("/tmp", missingResource);

        Files.deleteIfExists(missingPath);
        TruststoreExtractor.extract(missingResource, KAFKA_PROPERTY);

        assertNull(System.getProperty(KAFKA_PROPERTY));
        assertTrue(Files.notExists(missingPath));
    }
}
