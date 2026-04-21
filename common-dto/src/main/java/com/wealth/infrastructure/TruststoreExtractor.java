package com.wealth.infrastructure;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Utility to extract truststore JKS files from the classpath into temporary files.
 * Required because libraries like Kafka and certain Redis configurations require 
 * physical file paths and cannot read directly from a JAR's classpath resource.
 */
public class TruststoreExtractor {

    private static final String TMP_DIR_PATH = "/tmp";

    /**
     * Extracts a truststore resource from the classpath to /tmp and sets a system property.
     * 
     * @param resourceName The name of the resource (e.g., "truststore.jks")
     * @param propertyName The system property to set (e.g., "KAFKA_TRUSTSTORE_PATH")
     */
    public static void extract(String resourceName, String propertyName) {
        String normalizedResource = resourceName.startsWith("/") ? resourceName : "/" + resourceName;
        String destPath = TMP_DIR_PATH + normalizedResource;

        File tmpDir = new File(TMP_DIR_PATH);
        if (!tmpDir.exists() && !tmpDir.mkdirs()) {
            // If /tmp doesn't exist (e.g. Windows), skip extraction and let Spring try classpath defaults.
            return;
        }

        try (InputStream is = TruststoreExtractor.class.getResourceAsStream(normalizedResource)) {
            if (is == null) {
                System.err.println("WARN: Truststore resource not found in classpath: " + normalizedResource);
                return;
            }

            File destFile = new File(destPath);
            try (FileOutputStream os = new FileOutputStream(destFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
            }
            
            System.out.println("INFO: Extracted truststore to " + destFile.getAbsolutePath());
            // Set as a system property so it can be resolved via ${property.name} in application.yml
            System.setProperty(propertyName, "file:" + destFile.getAbsolutePath());
            
        } catch (IOException e) {
            System.err.println("ERROR: Failed to extract truststore '" + resourceName + "': " + e.getMessage());
        }
    }
}
