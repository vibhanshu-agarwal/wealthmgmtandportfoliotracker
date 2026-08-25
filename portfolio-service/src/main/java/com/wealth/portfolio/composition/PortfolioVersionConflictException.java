package com.wealth.portfolio.composition;

import java.util.Optional;
import java.util.UUID;

/**
 * Version precondition failure. When {@link #currentVersion()} is present it is the known current
 * (including virtual {@code 0} for Absent_Aggregate). When empty, the HTTP handler must re-read
 * after the failed transaction ends using {@link #lookupUserId()} or {@link #lookupPortfolioId()}
 * (D5).
 */
public final class PortfolioVersionConflictException extends RuntimeException {

    private final Long currentVersion;
    private final String lookupUserId;
    private final UUID lookupPortfolioId;

    public PortfolioVersionConflictException(long currentVersion) {
        super("portfolio_version_conflict: currentVersion=" + currentVersion);
        this.currentVersion = currentVersion;
        this.lookupUserId = null;
        this.lookupPortfolioId = null;
    }

    private PortfolioVersionConflictException(String lookupUserId, UUID lookupPortfolioId) {
        super("portfolio_version_conflict: currentVersion unresolved (re-read after rollback)");
        this.currentVersion = null;
        this.lookupUserId = lookupUserId;
        this.lookupPortfolioId = lookupPortfolioId;
    }

    /** Uniqueness-race path: re-read by user id after rollback. */
    public static PortfolioVersionConflictException unresolvedForUser(String userId) {
        return new PortfolioVersionConflictException(userId, null);
    }

    /** Failed-CAS path: re-read by portfolio id after rollback. */
    public static PortfolioVersionConflictException unresolvedForPortfolio(UUID portfolioId) {
        return new PortfolioVersionConflictException(null, portfolioId);
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

    public Optional<String> lookupUserId() {
        return Optional.ofNullable(lookupUserId);
    }

    public Optional<UUID> lookupPortfolioId() {
        return Optional.ofNullable(lookupPortfolioId);
    }
}
