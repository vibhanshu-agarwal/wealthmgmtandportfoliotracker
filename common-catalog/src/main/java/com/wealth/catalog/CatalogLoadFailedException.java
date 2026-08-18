package com.wealth.catalog;

import java.util.List;

public final class CatalogLoadFailedException extends RuntimeException {

    private final String resourcePath;
    private final List<String> violations;

    public CatalogLoadFailedException(String resourcePath, List<String> violations) {
        super(formatMessage(resourcePath, violations));
        this.resourcePath = resourcePath;
        this.violations = List.copyOf(violations);
    }

    public CatalogLoadFailedException(String resourcePath, String message) {
        this(resourcePath, List.of(message));
    }

    public String resourcePath() {
        return resourcePath;
    }

    public List<String> violations() {
        return violations;
    }

    private static String formatMessage(String resourcePath, List<String> violations) {
        return "Catalog load failed for " + resourcePath + " with " + violations.size()
                + " violation(s):\n" + String.join("\n", violations);
    }
}
