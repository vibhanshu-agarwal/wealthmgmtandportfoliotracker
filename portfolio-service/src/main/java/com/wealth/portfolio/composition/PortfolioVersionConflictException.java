package com.wealth.portfolio.composition;

/**
 * Version precondition failure. When {@link #currentVersion()} is present it is the known current
 * (including virtual {@code 0} for Absent_Aggregate). When empty, the HTTP handler must re-read
 * after the failed transaction ends (named uniqueness race).
 */
public final class PortfolioVersionConflictException extends RuntimeException {

    private final Long currentVersion;

    public PortfolioVersionConflictException(long currentVersion) {
        super("portfolio_version_conflict: currentVersion=" + currentVersion);
        this.currentVersion = currentVersion;
    }

    /** Constraint-race path: current version is not safely observable inside the rolled-back tx. */
    public PortfolioVersionConflictException() {
        super("portfolio_version_conflict: currentVersion unresolved (re-read after rollback)");
        this.currentVersion = null;
    }

    public ContractErrorCode code() {
        return ContractErrorCode.portfolio_version_conflict;
    }

    /** Empty when the caller must re-read after rollback (D5). */
    public java.util.OptionalLong currentVersion() {
        return currentVersion == null
                ? java.util.OptionalLong.empty()
                : java.util.OptionalLong.of(currentVersion);
    }
}
