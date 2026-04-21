package com.wealth.infrastructure;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * Utility to extract the Kafka truststore from the classpath into a temporary file.
 * Required because the Kafka client library requires a physical file path (it cannot
 * read from a JAR's classpath resource directly).
 */
public class KafkaTruststoreExtractor {

    private static final String RESOURCE_NAME = "/kafka-truststore.jks";
    private static final String DEST_PATH = "/tmp/kafka-truststore.jks";
    private static final String ENV_VAR = "KAFKA_TRUSTSTORE_PATH";

    public static void extract() {
        // Only run on systems that support /tmp (Linux/Lambda) or if explicitly requested.
        // On Windows local dev, the classpath: prefix might work if not packaged as a JAR,
        // but for consistency we use /tmp in production environments.
        
        File tmpDir = new File("/tmp");
        if (!tmpDir.exists() && !tmpDir.mkdirs()) {
            // If /tmp doesn't exist (e.g. Windows), skip extraction and let Spring try classpath.
            return;
        }

        try (InputStream is = KafkaTruststoreExtractor.class.getResourceAsStream(RESOURCE_NAME)) {
            if (is == null) {
                System.err.println("WARN: Kafka truststore resource not found in classpath: " + RESOURCE_NAME);
                return;
            }

            File destFile = new File(DEST_PATH);
            try (FileOutputStream os = new FileOutputStream(destFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
            }
            
            System.out.println("INFO: Extracted Kafka truststore to " + destFile.getAbsolutePath());
            System.setProperty(ENV_VAR, "file:" + destFile.getAbsolutePath());
            
        } catch (IOException e) {
            System.err.println("ERROR: Failed to extract Kafka truststore: " + e.getMessage());
        }
    }
}
