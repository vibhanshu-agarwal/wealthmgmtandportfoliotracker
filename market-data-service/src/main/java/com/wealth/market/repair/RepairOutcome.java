package com.wealth.market.repair;

public enum RepairOutcome {
    COMPLETE(0),
    ALREADY_COMPLETE(0),
    FAILED_CONFLICT(1),
    LOST_FENCE(1),
    FOREIGN_LEASE(1),
    TIMEOUT(1),
    UNVERIFIABLE(1);

    private final int exitCode;

    RepairOutcome(int exitCode) {
        this.exitCode = exitCode;
    }

    public int exitCode() {
        return exitCode;
    }

    public boolean success() {
        return exitCode == 0;
    }
}
