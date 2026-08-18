package com.wealth.market;

import org.springframework.core.env.Environment;

/**
 * Fails startup on any job-runner combination that is not one of the four valid rows.
 * Invalid combinations must not be resolved by precedence: a suspended runner's
 * {@code SpringApplication.exit(0)} would otherwise kill an in-flight repair.
 */
public final class JobRunnerMatrixValidator {

    public static final String REFRESH_PROPERTY = "market-data.job-runner.enabled";
    public static final String REPAIR_PROPERTY = "market-data.repair.enabled";

    private JobRunnerMatrixValidator() {}

    public static void validate(Environment environment) {
        validate(
                environment.getProperty(REFRESH_PROPERTY, Boolean.class),
                environment.getProperty(REPAIR_PROPERTY, Boolean.class));
    }

    /**
     * @param refreshEnabled {@code true}/{@code false} when the property is present; {@code null} when absent
     * @param repairEnabled {@code true}/{@code false} when the property is present; {@code null} when absent
     */
    public static void validate(Boolean refreshEnabled, Boolean repairEnabled) {
        boolean valid =
                (refreshEnabled == null && repairEnabled == null)
                        || (Boolean.TRUE.equals(refreshEnabled) && repairEnabled == null)
                        || (Boolean.FALSE.equals(refreshEnabled) && repairEnabled == null)
                        || (refreshEnabled == null && Boolean.TRUE.equals(repairEnabled));
        if (!valid) {
            throw new IllegalStateException(
                    "Invalid market-data job runner combination: "
                            + REFRESH_PROPERTY
                            + "="
                            + format(refreshEnabled)
                            + " "
                            + REPAIR_PROPERTY
                            + "="
                            + format(repairEnabled)
                            + ". Valid rows: both absent (neither); refresh=true and repair absent "
                            + "(refresh only); refresh=false and repair absent (suspended only); "
                            + "refresh absent and repair=true (repair only). Any other combination "
                            + "fails startup rather than resolving by precedence.");
        }
    }

    private static String format(Boolean value) {
        return value == null ? "<absent>" : value.toString();
    }
}
