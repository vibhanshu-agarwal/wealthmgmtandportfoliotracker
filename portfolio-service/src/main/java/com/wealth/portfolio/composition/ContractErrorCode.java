package com.wealth.portfolio.composition;

/** Stable machine-readable identifiers for composition contract failures (Requirement 7). */
public enum ContractErrorCode {
    portfolio_version_conflict,
    unsupported_asset,
    lifecycle_not_permitted,
    quantity_out_of_domain,
    duplicate_ticker
}
