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

    private static final String TMP_DIR = System.getProperty("java.io.tmpdir");

    /**
     * Extracts a truststore resource from the classpath to the system temp directory 
     * and sets a system property.
     * 
     * @param resourceName The name of the resource (e.g., "kafka-truststore.jks")
     * @param propertyName The system property to set (e.g., "KAFKA_TRUSTSTORE_PATH")
     */
    public static void extract(String resourceName, String propertyName) {
        String normalizedResource = resourceName.startsWith("/") ? resourceName : "/" + resourceName;
        
        File tempFile;
        try {
            tempFile = new File(TMP_DIR, resourceName);
            // Ensure parent directories exist
            File parent = tempFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                System.err.println("ERROR: Could not create temp directory: " + parent.getAbsolutePath());
                return;
            }
        } catch (Exception e) {
            System.err.println("ERROR: Invalid temp path: " + e.getMessage());
            return;
        }

        try (InputStream is = TruststoreExtractor.class.getResourceAsStream(normalizedResource)) {
            if (is == null) {
                System.err.println("WARN: Truststore resource not found in classpath: " + normalizedResource);
                return;
            }

            try (FileOutputStream os = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
            }
            
            String absolutePath = tempFile.getAbsolutePath();
            System.out.println("INFO: Extracted truststore '" + resourceName + "' to " + absolutePath);
            
            // Format as a file URL for Spring's ResourceLoader (e.g., file:/tmp/kafka-truststore.jks)
            // On Windows, it might be file:C:\Users\... which Spring handles fine.
            String propertyValue = absolutePath.startsWith("/") ? "file:" + absolutePath : "file:/" + absolutePath.replace("\\", "/");
            
            System.setProperty(propertyName, propertyValue);
            System.out.println("INFO: Set system property " + propertyName + " = " + propertyValue);
            
        } catch (IOException e) {
            System.err.println("ERROR: Failed to extract truststore '" + resourceName + "': " + e.getMessage());
        }
    }
}
