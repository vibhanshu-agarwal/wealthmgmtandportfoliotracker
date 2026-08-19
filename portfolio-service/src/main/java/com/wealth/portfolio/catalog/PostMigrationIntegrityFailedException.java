package com.wealth.portfolio.catalog;

import java.util.List;

public final class PostMigrationIntegrityFailedException extends RuntimeException {

    private final List<String> violations;

    public PostMigrationIntegrityFailedException(List<String> violations) {
        super(formatMessage(violations));
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
        return violations;
    }

    private static String formatMessage(List<String> violations) {
        return "Post-migration integrity assertion failed with "
                + violations.size()
                + " violation(s):\n"
                + String.join("\n", violations);
    }
}
